package site.pplee.jcode.server;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import site.pplee.jcode.app.ApiException;
import site.pplee.jcode.app.ManagedSession;
import site.pplee.jcode.app.SessionRegistry;
import site.pplee.jcode.codingagent.SessionFiles;
import site.pplee.jcode.codingagent.SessionListResult;
import site.pplee.jcode.codingagent.SessionAssemblyException;
import site.pplee.jcode.protocol.ApprovalCommand;
import site.pplee.jcode.protocol.ErrorCode;
import site.pplee.jcode.protocol.InputCommand;
import site.pplee.jcode.protocol.ManagedSessionView;
import site.pplee.jcode.protocol.RunCommand;
import site.pplee.jcode.protocol.SessionFileDiagnosticView;
import site.pplee.jcode.protocol.SessionFileView;
import site.pplee.jcode.protocol.SessionFilesView;
import site.pplee.jcode.protocol.WorkspaceView;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Thin HTTP mapping over the application service and read-only session discovery. */
final class HttpApi {
    static final int MAX_BODY_BYTES = 1_048_576;

    private final JcodeServer server;
    private final ServerConfig config;
    private final SessionRegistry sessions;
    private final ObjectMapper mapper;
    private final SseHandler sse;

    HttpApi(JcodeServer server, ServerConfig config, SessionRegistry sessions, ObjectMapper mapper) {
        this.server = server;
        this.config = config;
        this.sessions = sessions;
        this.mapper = mapper;
        this.sse = new SseHandler(mapper);
    }

    /** A null response means an SSE exchange has been fully handled. */
    Response dispatch(HttpExchange exchange, ApiRoutes.Route route) throws IOException {
        return switch (route.kind()) {
            case WORKSPACES -> new Response(200, workspaces());
            case SESSIONS -> "GET".equals(exchange.getRequestMethod())
                    ? new Response(200, managedSessions())
                    : new Response(201, create(readJson(exchange, CreateRequest.class)));
            case SESSION_FILES -> new Response(200, files(requiredQuery(exchange, "workspaceId")));
            case OPEN -> new Response(200, open(readJson(exchange, OpenRequest.class)));
            case SNAPSHOT -> new Response(200, requiredSession(route).snapshot());
            case HISTORY -> new Response(200, history(exchange, requiredSession(route)));
            case RUNS -> new Response(202, requiredSession(route)
                    .start(readJson(exchange, RunCommand.class)));
            case RUN -> new Response(200, requiredSession(route).view(route.itemId())
                    .orElseThrow(() -> notFound("run is not retained")));
            case SUBMIT_INPUT -> {
                InputCommand command = readJson(exchange, InputCommand.class);
                if (!route.runId().equals(command.targetRunId())) {
                    throw invalid("targetRunId does not match the path");
                }
                yield new Response(202, requiredSession(route).submit(command));
            }
            case INPUT -> new Response(200, requiredSession(route).input(route.itemId())
                    .orElseThrow(() -> notFound("input is not retained")));
            case CANCEL -> new Response(200, requiredSession(route).cancel(route.runId()));
            case APPROVAL -> new Response(200, requiredSession(route).approval(route.itemId())
                    .orElseThrow(() -> notFound("approval is not retained")));
            case RESOLVE_APPROVAL -> {
                ApprovalCommand command = readJson(exchange, ApprovalCommand.class);
                if (!route.itemId().equals(command.approvalId())) {
                    throw invalid("approvalId does not match the path");
                }
                yield new Response(200, requiredSession(route).resolve(command));
            }
            case CLOSE -> {
                sessions.close(route.sessionId());
                yield new Response(204, null);
            }
            case EVENTS -> {
                sse.stream(exchange, requiredSession(route));
                yield null;
            }
            case CAPABILITIES, STOP -> throw new IllegalStateException("host route reached HttpApi");
        };
    }

    private List<WorkspaceView> workspaces() {
        return config.workspaces().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(item -> new WorkspaceView(item.getKey(), item.getValue().toString()))
                .toList();
    }

    private List<ManagedSessionView> managedSessions() {
        return sessions.managedSessions().stream().map(this::view).toList();
    }

    private ManagedSessionView create(CreateRequest request) {
        String workspaceId = requireWorkspace(request.workspaceId());
        try {
            return view(sessions.create(server.optionsForWorkspace(workspaceId),
                    server.sessionDirectory(workspaceId), server.approvalSettings()));
        } catch (IOException | SessionAssemblyException failure) {
            throw internal("session creation failed", failure);
        }
    }

    private ManagedSessionView open(OpenRequest request) {
        String workspaceId = requireWorkspace(request.workspaceId());
        Path file = selectedFile(server.sessionDirectory(workspaceId), request.fileRef());
        try {
            return view(sessions.open(server.optionsForWorkspace(workspaceId), file,
                    server.approvalSettings()));
        } catch (IOException | SessionAssemblyException failure) {
            throw internal("session open failed", failure);
        }
    }

    private SessionFilesView files(String workspaceId) {
        requireWorkspace(workspaceId);
        try {
            SessionListResult found = SessionFiles.list(server.sessionDirectory(workspaceId),
                    config.workspaces().get(workspaceId));
            var listed = found.sessions().stream().map(info -> new SessionFileView(
                    info.path().getFileName().toString(), info.id().toString(),
                    info.name().orElse(null), info.created().toString(),
                    info.modified().toString(),
                    info.messageCount())).toList();
            var diagnostics = found.diagnostics().stream()
                    .map(item -> new SessionFileDiagnosticView(
                            item.path().getFileName().toString(), item.kind().name(),
                            item.lineNumber(), item.byteOffset(), item.detail()))
                    .toList();
            return new SessionFilesView(workspaceId, listed, diagnostics);
        } catch (IOException failure) {
            throw internal("session discovery failed", failure);
        }
    }

    private Object history(HttpExchange exchange, ManagedSession session) {
        Map<String, String> query = query(exchange);
        for (String key : query.keySet()) {
            if (!List.of("headEntryId", "beforeEntryId", "limit").contains(key)) {
                throw invalid("unknown history query parameter");
            }
        }
        int limit = 50;
        if (query.containsKey("limit")) {
            try {
                limit = Integer.parseInt(query.get("limit"));
            } catch (NumberFormatException failure) {
                throw invalid("limit must be an integer");
            }
        }
        return session.historyPage(query.get("headEntryId"), query.get("beforeEntryId"), limit);
    }

    private ManagedSession requiredSession(ApiRoutes.Route route) {
        return sessions.find(route.sessionId())
                .orElseThrow(() -> notFound("session is not managed"));
    }

    private ManagedSessionView view(ManagedSession session) {
        Path file = session.sessionFile().orElseThrow();
        try {
            Path fileDirectory = file.getParent().toRealPath();
            for (String workspaceId : config.workspaces().keySet()) {
                Path configuredDirectory = config.dataDirectory().resolve("sessions")
                        .resolve(workspaceId);
                if (Files.isDirectory(configuredDirectory)
                        && fileDirectory.equals(configuredDirectory.toRealPath())) {
                    return new ManagedSessionView(session.sessionId(), workspaceId,
                            file.getFileName().toString(), session.currentLeafId().orElse(null));
                }
            }
        } catch (IOException failure) {
            throw internal("session file ownership could not be resolved", failure);
        }
        throw new IllegalStateException("managed session file has no configured workspace");
    }

    private String requireWorkspace(String workspaceId) {
        if (workspaceId == null || !config.workspaces().containsKey(workspaceId)) {
            throw notFound("workspace is not configured");
        }
        return workspaceId;
    }

    private static Path selectedFile(Path directory, String fileRef) {
        if (fileRef == null || !fileRef.endsWith(".jsonl")
                || fileRef.isBlank() || fileRef.contains("/") || fileRef.contains("\\")) {
            throw invalid("fileRef must identify a JSONL file in the selected workspace");
        }
        try {
            Path parent = directory.toRealPath();
            Path file = parent.resolve(fileRef).toRealPath();
            if (!parent.equals(file.getParent()) || !Files.isRegularFile(file)) {
                throw invalid("fileRef leaves the selected workspace");
            }
            return file;
        } catch (IOException failure) {
            throw notFound("session file does not exist");
        }
    }

    private <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT)
                .matches("application/json(?:\\s*;\\s*charset=utf-8)?")) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "Content-Type must be application/json with UTF-8");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "JSON request exceeds 1 MiB");
        }
        try (JsonParser parser = mapper.createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw invalid("request must contain one JSON object");
            }
            return mapper.treeToValue(root, type);
        } catch (JsonProcessingException failure) {
            throw invalid("request JSON is invalid");
        }
    }

    private static String requiredQuery(HttpExchange exchange, String key) {
        Map<String, String> query = query(exchange);
        if (query.size() != 1 || !query.containsKey(key) || query.get(key).isBlank()) {
            throw invalid("required query parameter is missing or duplicated");
        }
        return query.get(key);
    }

    static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        var values = new HashMap<String, String>();
        for (String part : raw.split("&", -1)) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                throw invalid("invalid query parameter");
            }
            String key = URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
            if (values.putIfAbsent(key, value) != null) {
                throw invalid("duplicate query parameter");
            }
        }
        return Map.copyOf(values);
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_ARGUMENT, message);
    }

    private static ApiException notFound(String message) {
        return new ApiException(ErrorCode.NOT_FOUND, message);
    }

    private static ApiException internal(String message, Exception failure) {
        return new ApiException(ErrorCode.INTERNAL_ERROR, message, failure);
    }

    record Response(int status, Object body) { }

    private record CreateRequest(String workspaceId) { }

    private record OpenRequest(String workspaceId, String fileRef) { }
}
