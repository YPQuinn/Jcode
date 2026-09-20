package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/** Ignore-aware, bounded text search backed by an explicit ripgrep executable. */
public final class GrepTool implements AgentTool<GrepToolArguments>, AutoCloseable {
    public static final int MAX_MATCHES = 1_000;
    public static final int MAX_LINE_CODE_POINTS = 500;

    private static final Set<String> ARGUMENT_FIELDS = Set.of(
            "pattern", "path", "glob", "ignoreCase", "literal", "limit");
    private static final JsonNode SCHEMA = createSchema();

    private final Path workingDirectory;
    private final SearchBackend backend;
    private final boolean ownsBackend;

    public GrepTool(Path workingDirectory, SearchConfig config) {
        this.workingDirectory = validateWorkingDirectory(workingDirectory);
        this.backend = SearchProcessBackend.open(this.workingDirectory, config);
        this.ownsBackend = true;
    }

    GrepTool(Path workingDirectory, SearchBackend backend) {
        this.workingDirectory = validateWorkingDirectory(workingDirectory);
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
        this.ownsBackend = false;
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public Class<GrepToolArguments> argumentType() {
        return GrepToolArguments.class;
    }

    @Override
    public String description() {
        return "Search UTF-8 text with ignore-aware ripgrep semantics. Supports Rust regexes or "
                + "literal matching, optional case-insensitive matching, and a native glob filter. "
                + "Returns at most 1000 complete path/line records with 500-code-point summaries; "
                + "hidden files are included; explicit glob rules can override ignore rules.";
    }

    @Override
    public JsonNode parametersSchema() {
        return SCHEMA.deepCopy();
    }

    @Override
    public JsonNode prepareArguments(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("arguments must be an object");
        }
        var prepared = ((ObjectNode) arguments).deepCopy();
        prepared.fieldNames().forEachRemaining(field -> {
            if (!ARGUMENT_FIELDS.contains(field)) {
                throw new IllegalArgumentException("unknown argument field: " + field);
            }
        });
        JsonNode pattern = prepared.get("pattern");
        if (pattern == null || !pattern.isTextual()) {
            throw new IllegalArgumentException("pattern must be a string");
        }
        SearchToolSupport.validateRequiredPattern(pattern.textValue());
        preparePath(prepared);
        JsonNode glob = prepared.get("glob");
        if (glob != null) {
            if (!glob.isTextual()) {
                throw new IllegalArgumentException("glob must be a string");
            }
            SearchToolSupport.validateOptionalGlob(glob.textValue());
        }
        prepareBoolean(prepared, "ignoreCase", false);
        prepareBoolean(prepared, "literal", false);
        prepareLimit(prepared);
        return prepared;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(
            String toolCallId,
            GrepToolArguments arguments,
            ToolUpdateSink updates,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        try {
            cancellation.throwIfCancelled();
            var target = SearchToolSupport.resolveTarget(
                    workingDirectory, arguments.path(), true);
            var collector = new GrepCollector(arguments.limit());
            var command = grepCommand(arguments, target.backendPath());
            var execution = backend.execute(
                    target.workingDirectory(), command, collector, cancellation);
            String body = collector.body();
            var notices = collector.notices();
            Optional<String> failure = SearchToolSupport.backendFailure(execution);
            if (failure.isPresent()) {
                String output = SearchToolSupport.failureOutput(body, notices, failure.orElseThrow());
                return completed(new ToolExecutionResult(
                        List.of(new Content.Text(output)), true, false));
            }
            return completed(ToolExecutionResult.success(List.of(
                    new Content.Text(SearchToolSupport.formatOutput(body, notices)))));
        } catch (java.util.concurrent.CancellationException e) {
            return completed(ToolExecutionResult.failure("search cancelled"));
        } catch (IllegalArgumentException e) {
            return completed(ToolExecutionResult.failure("grep failed: " + safeArgumentMessage(e)));
        } catch (RuntimeException e) {
            return completed(ToolExecutionResult.failure("grep failed: search execution failed"));
        }
    }

    @Override
    public void close() {
        if (ownsBackend) {
            backend.close();
        }
    }

    private static List<String> grepCommand(GrepToolArguments arguments, String backendPath) {
        var command = new ArrayList<String>();
        command.add("--json");
        command.add("--hidden");
        if (arguments.glob() != null) {
            command.add("--glob");
            command.add(arguments.glob());
        }
        if (arguments.ignoreCase()) {
            command.add("--ignore-case");
        }
        if (arguments.literal()) {
            command.add("--fixed-strings");
        }
        command.add("-e");
        command.add(arguments.pattern());
        command.add("--");
        command.add(backendPath);
        return List.copyOf(command);
    }

    private static void preparePath(ObjectNode prepared) {
        JsonNode path = prepared.get("path");
        if (path == null) {
            prepared.put("path", GrepToolArguments.DEFAULT_PATH);
        } else if (!path.isTextual()) {
            throw new IllegalArgumentException("path must be a string");
        } else {
            FileToolSupport.validatePath(path.textValue());
        }
    }

    private static void prepareBoolean(ObjectNode prepared, String field, boolean defaultValue) {
        JsonNode value = prepared.get(field);
        if (value == null) {
            prepared.put(field, defaultValue);
        } else if (!value.isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
    }

    private static void prepareLimit(ObjectNode prepared) {
        JsonNode limit = prepared.get("limit");
        if (limit == null) {
            prepared.put("limit", GrepToolArguments.DEFAULT_LIMIT);
        } else if (!limit.isIntegralNumber() || !limit.canConvertToInt()) {
            throw new IllegalArgumentException("limit must be a 32-bit integer");
        } else if (limit.intValue() < 1 || limit.intValue() > MAX_MATCHES) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_MATCHES);
        }
    }

    private static Path validateWorkingDirectory(Path workingDirectory) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Path normalized = workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("workingDirectory must be an existing directory");
        }
        return normalized;
    }

    private static String safeArgumentMessage(IllegalArgumentException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "invalid arguments" : message;
    }

    private static JsonNode createSchema() {
        var factory = JsonNodeFactory.instance;
        var properties = factory.objectNode();
        properties.set("pattern", factory.objectNode()
                .put("type", "string")
                .put("description", "Rust regex or literal text to search for")
                .put("minLength", 1)
                .put("maxLength", SearchToolSupport.MAX_PATTERN_CHARACTERS));
        properties.set("path", factory.objectNode()
                .put("type", "string")
                .put("description", "File or directory to search; defaults to the working directory")
                .put("maxLength", FileToolSupport.MAX_PATH_CHARACTERS));
        properties.set("glob", factory.objectNode()
                .put("type", "string")
                .put("description", "Native glob filter; supports braces, character groups and negation")
                .put("minLength", 1)
                .put("maxLength", SearchToolSupport.MAX_PATTERN_CHARACTERS));
        properties.set("ignoreCase", factory.objectNode()
                .put("type", "boolean")
                .put("description", "Use case-insensitive matching; defaults to false"));
        properties.set("literal", factory.objectNode()
                .put("type", "boolean")
                .put("description", "Treat pattern as literal text; defaults to false"));
        properties.set("limit", factory.objectNode()
                .put("type", "integer")
                .put("description", "Maximum matching lines; defaults to 100")
                .put("minimum", 1)
                .put("maximum", MAX_MATCHES));
        var schema = factory.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("pattern");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static CompletionStage<ToolExecutionResult> completed(ToolExecutionResult result) {
        return CompletableFuture.completedFuture(result);
    }

    private static final class GrepCollector implements SearchBackend.OutputCollector {
        private final ObjectMapper mapper = new ObjectMapper();
        private final BoundedRecordFormatter output = new BoundedRecordFormatter(
                MAX_MATCHES, SearchToolSupport.MAX_OUTPUT_BYTES);
        private final BoundedByteRecordReader records;
        private final int matchLimit;
        private final AtomicReference<SearchBackend.StopReason> stopReason = new AtomicReference<>();

        private int matches;
        private int unsafeOrOversizedRecords;
        private boolean outputByteLimitReached;

        private GrepCollector(int matchLimit) {
            this.matchLimit = matchLimit;
            this.records = new BoundedByteRecordReader(
                    (byte) '\n',
                    SearchToolSupport.MAX_STRUCTURED_RECORD_BYTES,
                    SearchToolSupport.MAX_SCANNED_RECORDS,
                    this::acceptRecord);
        }

        @Override
        public void append(byte[] bytes, int offset, int length) {
            if (stopReason.get() != null) {
                return;
            }
            records.append(bytes, offset, length);
            if (records.scanLimitReached()) {
                stopReason.compareAndSet(null, SearchBackend.StopReason.SCAN_LIMIT);
            }
        }

        @Override
        public void finish() {
            if (stopReason.get() == SearchBackend.StopReason.RESULT_LIMIT) {
                return;
            }
            try {
                records.finish(true);
                if (records.scanLimitReached()) {
                    stopReason.compareAndSet(null, SearchBackend.StopReason.SCAN_LIMIT);
                }
            } catch (RuntimeException e) {
                stopReason.set(SearchBackend.StopReason.PARSE_FAILURE);
            }
        }

        @Override
        public Optional<SearchBackend.StopReason> stopReason() {
            return Optional.ofNullable(stopReason.get());
        }

        String body() {
            return output.recordCount() == 0 ? "(no matches)" : output.content();
        }

        List<String> notices() {
            var notices = new ArrayList<String>();
            if (outputByteLimitReached) {
                notices.add("Output byte limit reached; additional matches were omitted");
            } else if (stopReason.get() == SearchBackend.StopReason.RESULT_LIMIT) {
                notices.add("Match limit reached at " + matchLimit + "; narrow the search");
            }
            if (stopReason.get() == SearchBackend.StopReason.SCAN_LIMIT) {
                notices.add("Structured record scan limit reached after "
                        + SearchToolSupport.MAX_SCANNED_RECORDS + " records");
            }
            int omitted = unsafeOrOversizedRecords + records.oversizedRecords();
            if (omitted > 0) {
                notices.add(omitted
                        + " matches omitted because a complete safe record could not be represented");
            }
            return List.copyOf(notices);
        }

        private void acceptRecord(byte[] record) {
            if (stopReason.get() != null) {
                return;
            }
            final JsonNode event;
            try {
                event = mapper.readTree(record);
                if (event == null || !event.isObject()) {
                    throw new IllegalArgumentException("event must be an object");
                }
                JsonNode type = event.get("type");
                if (type == null || !type.isTextual()) {
                    throw new IllegalArgumentException("event type is missing");
                }
                if (!type.textValue().equals("match")) {
                    return;
                }
                acceptMatch(event.get("data"));
            } catch (IOException | RuntimeException e) {
                stopReason.set(SearchBackend.StopReason.PARSE_FAILURE);
            }
        }

        private void acceptMatch(JsonNode data) {
            if (data == null || !data.isObject()) {
                throw new IllegalArgumentException("match data is missing");
            }
            final String path;
            final String line;
            try {
                path = SearchToolSupport.normalizeRelativePath(
                        SearchToolSupport.decodeTextOrBytes(data.get("path"), "match path"));
                line = SearchToolSupport.decodeTextOrBytes(data.get("lines"), "match line");
            } catch (IllegalArgumentException e) {
                unsafeOrOversizedRecords++;
                return;
            }
            JsonNode lineNumber = data.get("line_number");
            if (lineNumber == null || !lineNumber.isIntegralNumber()
                    || !lineNumber.canConvertToLong() || lineNumber.longValue() < 1) {
                throw new IllegalArgumentException("match line number is invalid");
            }
            String summary = SearchToolSupport.truncateCodePoints(
                    SearchToolSupport.stripLineEnding(line), MAX_LINE_CODE_POINTS);
            String formatted = SearchToolSupport.quote(path) + ':' + lineNumber.longValue()
                    + ": " + SearchToolSupport.quote(summary);
            var appendResult = output.append(formatted);
            if (appendResult == BoundedRecordFormatter.AppendResult.ADDED) {
                matches++;
                if (matches >= matchLimit) {
                    stopReason.compareAndSet(null, SearchBackend.StopReason.RESULT_LIMIT);
                }
            } else if (appendResult == BoundedRecordFormatter.AppendResult.OUTPUT_FULL) {
                outputByteLimitReached = true;
                stopReason.compareAndSet(null, SearchBackend.StopReason.RESULT_LIMIT);
            } else {
                unsafeOrOversizedRecords++;
            }
        }
    }
}
