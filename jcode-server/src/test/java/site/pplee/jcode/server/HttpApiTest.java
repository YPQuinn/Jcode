package site.pplee.jcode.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.provider.DefaultModels;
import site.pplee.jcode.ai.provider.ModelProvider;
import site.pplee.jcode.ai.provider.ProviderAuth;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.app.SessionRegistry;
import site.pplee.jcode.app.SessionSubscription;
import site.pplee.jcode.codingagent.CodingAgentSessionOptions;
import site.pplee.jcode.codingagent.InputDeliveryMode;
import site.pplee.jcode.codingagent.settings.CodingAgentSettings;
import site.pplee.jcode.codingagent.settings.SettingsOverrides;
import site.pplee.jcode.protocol.ApprovalView;
import site.pplee.jcode.protocol.EventType;
import site.pplee.jcode.protocol.RunStatus;
import site.pplee.jcode.protocol.RunCommand;
import site.pplee.jcode.protocol.RunKind;
import site.pplee.jcode.protocol.SessionEvent;
import site.pplee.jcode.protocol.SessionReducer;
import site.pplee.jcode.protocol.SessionSnapshot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HttpApiTest {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "one");

    @TempDir
    Path directory;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void createsRunsPagesHistoryAndReopensAFileThroughHttp() throws Exception {
        try (var server = start(new ReplyProvider())) {
            String token = token(server);
            assertEquals(200, send(server, "GET", "/v1/workspaces", token, null).statusCode());
            assertEquals(400, send(server, "POST", "/v1/sessions", token,
                    "{\"workspaceId\":\"project\"} trailing").statusCode());
            assertEquals(415, send(server, "POST", "/v1/sessions", token,
                    "{\"workspaceId\":\"project\"}", "text/plain").statusCode());
            assertEquals(413, send(server, "POST", "/v1/sessions", token,
                    "{\"workspaceId\":\"" + "a".repeat(HttpApi.MAX_BODY_BYTES) + "\"}")
                    .statusCode());

            var created = send(server, "POST", "/v1/sessions", token,
                    "{\"workspaceId\":\"project\"}");
            assertEquals(201, created.statusCode());
            JsonNode owner = json(created.body());
            String sessionId = owner.path("sessionId").asText();
            String fileRef = owner.path("fileRef").asText();
            assertEquals(1, json(send(server, "GET", "/v1/sessions", token, null).body()).size());
            assertEquals(sessionId, json(send(server, "GET",
                    "/v1/session-files?workspaceId=project", token, null).body())
                    .path("sessions").get(0).path("sessionId").asText());
            assertEquals(0, json(send(server, "GET",
                    "/v1/sessions/" + sessionId + "/history", token, null).body())
                    .path("entries").size());

            String runPath = "/v1/sessions/" + sessionId + "/runs";
            String command = "{\"commandId\":\"cmd-1\",\"runId\":\"run-1\","
                    + "\"kind\":\"PROMPT\",\"text\":\"你好\"}";
            assertEquals(202, send(server, "POST", runPath, token, command).statusCode());
            server.sessions().find(sessionId).orElseThrow().settled("run-1")
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(202, send(server, "POST", runPath, token, command).statusCode());
            assertEquals("COMPLETED", json(send(server, "GET", runPath + "/run-1", token,
                    null).body()).path("status").asText());
            assertEquals(409, send(server, "POST", runPath, token,
                    command.replace("你好", "different")).statusCode());
            String fixedHead = json(send(server, "GET", "/v1/sessions/" + sessionId
                    + "/history?limit=1", token, null).body())
                    .path("headEntryId").asText();
            assertEquals(202, send(server, "POST", runPath, token,
                    "{\"commandId\":\"cmd-opaque\",\"runId\":\"run:a/b\","
                            + "\"kind\":\"PROMPT\",\"text\":\"opaque\"}").statusCode());
            server.sessions().find(sessionId).orElseThrow().settled("run:a/b")
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(200, send(server, "GET", runPath + "/run%3Aa%2Fb", token,
                    null).statusCode());
            assertEquals(fixedHead, json(send(server, "GET", "/v1/sessions/" + sessionId
                    + "/history?headEntryId=" + fixedHead + "&limit=1", token, null).body())
                    .path("headEntryId").asText());

            JsonNode page = json(send(server, "GET", "/v1/sessions/" + sessionId
                    + "/history?limit=1", token, null).body());
            assertEquals(1, page.path("entries").size());
            assertTrue(page.path("headEntryId").isTextual());
            assertTrue(page.path("entries").get(0).path("entryId").isTextual());
            if (!page.path("nextBeforeEntryId").isNull()) {
                JsonNode older = json(send(server, "GET", "/v1/sessions/" + sessionId
                        + "/history?headEntryId=" + page.path("headEntryId").asText()
                        + "&beforeEntryId=" + page.path("nextBeforeEntryId").asText()
                        + "&limit=1", token, null).body());
                assertEquals(page.path("headEntryId"), older.path("headEntryId"));
            }
            assertEquals(400, send(server, "GET", "/v1/sessions/" + sessionId
                    + "/history?beforeEntryId=unrelated", token, null).statusCode());
            assertEquals(204, send(server, "POST", "/v1/sessions/" + sessionId
                    + "/close", token, null).statusCode());
            assertEquals(0, json(send(server, "GET", "/v1/sessions", token, null).body()).size());
            assertEquals(200, send(server, "POST", "/v1/sessions/open", token,
                    "{\"workspaceId\":\"project\",\"fileRef\":\"" + fileRef + "\"}")
                    .statusCode());
            assertEquals(400, send(server, "POST", "/v1/sessions/open", token,
                    "{\"workspaceId\":\"project\",\"fileRef\":\"../other.jsonl\"}")
                    .statusCode());
        }
    }

    @Test
    void sessionFileOwnerSurvivesWorkspaceAliasesAndReopen() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        var config = new ServerConfig(0, directory.resolve("data"),
                directory.resolve("user-config"),
                Map.of("alpha", workspace, "zeta", workspace),
                Set.of(), Set.of(), Duration.ofMinutes(5));
        try (var server = JcodeServer.start(config, new SessionRegistry(),
                exchange -> { }, base -> borrowed(base, new ReplyProvider()))) {
            String token = token(server);
            JsonNode created = json(send(server, "POST", "/v1/sessions", token,
                    "{\"workspaceId\":\"zeta\"}").body());
            assertEquals("zeta", created.path("workspaceId").asText());
            String sessionId = created.path("sessionId").asText();
            String fileRef = created.path("fileRef").asText();

            JsonNode listed = json(send(server, "GET", "/v1/sessions", token, null).body());
            assertEquals(1, listed.size());
            assertEquals("zeta", listed.get(0).path("workspaceId").asText());
            assertEquals(fileRef, listed.get(0).path("fileRef").asText());
            assertEquals(204, send(server, "POST", "/v1/sessions/" + sessionId
                    + "/close", token, null).statusCode());

            JsonNode reopened = json(send(server, "POST", "/v1/sessions/open", token,
                    "{\"workspaceId\":\"zeta\",\"fileRef\":\"" + fileRef + "\"}").body());
            assertEquals(sessionId, reopened.path("sessionId").asText());
            assertEquals("zeta", reopened.path("workspaceId").asText());
            assertEquals(404, send(server, "POST", "/v1/sessions/open", token,
                    "{\"workspaceId\":\"alpha\",\"fileRef\":\"" + fileRef + "\"}")
                    .statusCode());
        }
    }

    @Test
    void sseReplaysEventsAndDrainsTerminalBeforeCloseControl() throws Exception {
        try (var server = start(new ReplyProvider())) {
            String token = token(server);
            String sessionId = json(send(server, "POST", "/v1/sessions", token,
                    "{\"workspaceId\":\"project\"}").body()).path("sessionId").asText();
            String base = "/v1/sessions/" + sessionId;
            var snapshot = mapper.readValue(send(server, "GET", base + "/snapshot", token,
                    null).body(), SessionSnapshot.class);
            String after = snapshot.cursor().epoch() + ":" + snapshot.cursor().seq();
            var streamRequest = HttpRequest.newBuilder(server.endpoint().resolve(
                            base + "/events?after=" + after))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var stream = client.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, stream.statusCode());
            try (var reader = new BufferedReader(new InputStreamReader(
                    stream.body(), StandardCharsets.UTF_8))) {
                assertEquals(202, send(server, "POST", base + "/runs", token,
                        "{\"commandId\":\"cmd-1\",\"runId\":\"run-1\","
                                + "\"kind\":\"PROMPT\",\"text\":\"你好\"}").statusCode());
                server.sessions().find(sessionId).orElseThrow().settled("run-1")
                        .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
                assertEquals(204, send(server, "POST", base + "/close", token, null).statusCode());

                var projected = snapshot;
                var eventIds = new ArrayList<String>();
                boolean control = false;
                for (int index = 0; index < 30; index++) {
                    String frame = nextFrame(reader);
                    if (frame.contains("event: stream.control")) {
                        assertTrue(frame.contains("SESSION_CLOSED"));
                        assertFalse(frame.contains("id: "));
                        control = true;
                        break;
                    }
                    assertTrue(frame.contains("event: session.event"));
                    String id = line(frame, "id: ");
                    var event = mapper.readValue(line(frame, "data: "), SessionEvent.class);
                    assertEquals(event.cursor().epoch() + ":" + event.cursor().seq(), id);
                    eventIds.add(id);
                    projected = SessionReducer.apply(projected, event, mapper);
                }
                assertTrue(control);
                assertFalse(eventIds.isEmpty());
                assertEquals(RunStatus.COMPLETED, projected.runs().getFirst().status());
            }
        }
    }

    @Test
    void reconnectAfterReadingOnlyAnEventIdReplaysTheWholeEvent() throws Exception {
        var provider = new PausedProvider();
        String origin = "http://127.0.0.1:3000";
        try (var server = start(provider, Set.of(), Set.of(origin))) {
            String token = token(server);
            String sessionId = json(send(server, "POST", "/v1/sessions", token,
                    "{\"workspaceId\":\"project\"}").body()).path("sessionId").asText();
            String base = "/v1/sessions/" + sessionId;
            var snapshot = mapper.readValue(send(server, "GET", base + "/snapshot", token,
                    null).body(), SessionSnapshot.class);
            String cursor = snapshot.cursor().epoch() + ":" + snapshot.cursor().seq();
            String events = base + "/events?after=" + cursor;
            var request = HttpRequest.newBuilder(server.endpoint().resolve(events))
                    .header("Authorization", "Bearer " + token).GET().build();
            var first = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, first.statusCode());
            String partialId;
            try (var reader = new BufferedReader(new InputStreamReader(
                    first.body(), StandardCharsets.UTF_8))) {
                assertEquals(202, send(server, "POST", base + "/runs", token,
                        "{\"commandId\":\"cmd-1\",\"runId\":\"run-1\","
                                + "\"kind\":\"PROMPT\",\"text\":\"你好\"}").statusCode());
                provider.awaitFirst();
                String idLine = reader.readLine();
                assertTrue(idLine.startsWith("id: "));
                partialId = idLine.substring("id: ".length());
                // The id line alone is not a complete event and must not advance the cursor.
            }

            provider.completeFirst();
            server.sessions().find(sessionId).orElseThrow().settled("run-1")
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            var preflight = HttpRequest.newBuilder(server.endpoint().resolve(events))
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "authorization,last-event-id")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            var allowed = client.send(preflight, HttpResponse.BodyHandlers.discarding());
            assertEquals(204, allowed.statusCode());
            assertTrue(allowed.headers().firstValue("Access-Control-Allow-Headers")
                    .orElseThrow().contains("Last-Event-ID"));
            var denied = HttpRequest.newBuilder(server.endpoint().resolve(events))
                    .header("Origin", "https://unlisted.example")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "authorization,last-event-id")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(403, client.send(denied,
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            var reconnect = HttpRequest.newBuilder(server.endpoint().resolve(
                            base + "/events?after=outdated"))
                    .header("Authorization", "Bearer " + token)
                    .header("Origin", origin)
                    .header("Last-Event-ID", cursor).GET().build();
            var replay = client.send(reconnect, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, replay.statusCode());
            try (var reader = new BufferedReader(new InputStreamReader(
                    replay.body(), StandardCharsets.UTF_8))) {
                String frame = nextFrame(reader);
                assertEquals(partialId, line(frame, "id: "));
                var event = mapper.readValue(line(frame, "data: "), SessionEvent.class);
                SessionReducer.apply(snapshot, event, mapper);
                assertEquals(1, provider.calls.get());
            }
        }
    }

    @Test
    void runScopedInputCanBeRetriedAndQueriedAfterItsRun() throws Exception {
        var provider = new PausedProvider();
        try (var server = start(provider)) {
            String token = token(server);
            String sessionId = json(send(server, "POST", "/v1/sessions", token,
                    "{\"workspaceId\":\"project\"}").body()).path("sessionId").asText();
            String base = "/v1/sessions/" + sessionId;
            assertEquals(202, send(server, "POST", base + "/runs", token,
                    "{\"commandId\":\"cmd-1\",\"runId\":\"run-1\","
                            + "\"kind\":\"PROMPT\",\"text\":\"first\"}").statusCode());
            String input = "{\"commandId\":\"cmd-input\",\"inputId\":\"input-1\","
                    + "\"targetRunId\":\"run-1\",\"mode\":\"FOLLOW_UP\","
                    + "\"text\":\"补充\"}";
            assertEquals(400, send(server, "POST", base + "/runs/other/inputs", token,
                    input).statusCode());
            assertEquals(202, send(server, "POST", base + "/runs/run-1/inputs", token,
                    input).statusCode());
            provider.completeFirst();
            server.sessions().find(sessionId).orElseThrow().settled("run-1")
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(202, send(server, "POST", base + "/runs/run-1/inputs", token,
                    input).statusCode());
            JsonNode applied = json(send(server, "GET", base + "/inputs/input-1", token,
                    null).body());
            assertEquals("APPLIED_TO_CONTEXT", applied.path("status").asText());
            assertTrue(applied.path("entryId").isTextual());
            assertEquals(2, provider.calls.get());
        }
    }

    @Test
    void pendingApprovalCanBeFoundAndDecidedAfterChangingSubscribers() throws Exception {
        var provider = new ToolProvider();
        try (var server = start(provider, Set.of("read"))) {
            String token = token(server);
            String sessionId = json(send(server, "POST", "/v1/sessions", token,
                    "{\"workspaceId\":\"project\"}").body()).path("sessionId").asText();
            String base = "/v1/sessions/" + sessionId;
            var managed = server.sessions().find(sessionId).orElseThrow();
            ApprovalView pending;
            try (SessionSubscription subscription = managed.subscribe(managed.snapshot().cursor())) {
                assertEquals(202, send(server, "POST", base + "/runs", token,
                        "{\"commandId\":\"cmd-1\",\"runId\":\"run-1\","
                                + "\"kind\":\"PROMPT\",\"text\":\"read\"}").statusCode());
                pending = approvalEvent(subscription);
            }
            assertEquals("PENDING", json(send(server, "GET", base + "/approvals/"
                    + pending.approvalId(), token, null).body()).path("status").asText());
            String decision = mapper.writeValueAsString(Map.of(
                    "approvalId", pending.approvalId(), "toolCallId", pending.toolCallId(),
                    "requestDigest", pending.requestDigest(), "decision", "DENY"));
            String path = base + "/approvals/" + pending.approvalId() + "/resolve";
            assertEquals("DENIED", json(send(server, "POST", path, token, decision).body())
                    .path("status").asText());
            assertEquals(200, send(server, "POST", path, token, decision).statusCode());
            assertEquals(409, send(server, "POST", path, token,
                    decision.replace("DENY", "ALLOW")).statusCode());
            managed.settled("run-1").toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(2, provider.calls.get());
        }
    }

    @Test
    void sseRejectsMissingInvalidAndExpiredCursorsBeforeSendingHeaders() throws Exception {
        try (var server = start(new ReplyProvider())) {
            String token = token(server);
            String sessionId = json(send(server, "POST", "/v1/sessions", token,
                    "{\"workspaceId\":\"project\"}").body()).path("sessionId").asText();
            String path = "/v1/sessions/" + sessionId + "/events";
            assertEquals(400, send(server, "GET", path, token, null).statusCode());
            assertEquals(401, client.send(HttpRequest.newBuilder(server.endpoint().resolve(path))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, client.send(HttpRequest.newBuilder(server.endpoint().resolve(path))
                    .header("Authorization", "Bearer " + token)
                    .header("Origin", "https://unlisted.example").GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(409, send(server, "GET", path + "?after=other:0", token,
                    null).statusCode());
            String after = json(send(server, "GET", "/v1/sessions/" + sessionId
                    + "/snapshot", token, null).body()).path("cursor").path("epoch").asText()
                    + ":0";
            var invalidHeader = HttpRequest.newBuilder(server.endpoint().resolve(path
                            + "?after=" + after))
                    .header("Authorization", "Bearer " + token)
                    .header("Last-Event-ID", "invalid")
                    .GET().build();
            assertEquals(400, client.send(invalidHeader,
                    HttpResponse.BodyHandlers.ofString()).statusCode());

            var managed = server.sessions().find(sessionId).orElseThrow();
            for (int index = 0; index < 180; index++) {
                String id = Integer.toString(index);
                managed.start(new RunCommand("cmd-" + id, "run-" + id,
                        RunKind.PROMPT, "short", null));
                managed.settled("run-" + id).toCompletableFuture()
                        .orTimeout(5, TimeUnit.SECONDS).join();
            }
            assertTrue(managed.snapshot().cursor().seq() > 512);
            var expired = send(server, "GET", path + "?after=" + after, token, null);
            assertEquals(409, expired.statusCode());
            assertEquals("CURSOR_EXPIRED", json(expired.body()).path("code").asText());
        }
    }

    @Test
    void cancellationResponseRecordsIntentWithoutPredictingTheFinalStatus() throws Exception {
        var provider = new PausedProvider();
        try (var server = start(provider)) {
            String token = token(server);
            String sessionId = json(send(server, "POST", "/v1/sessions", token,
                    "{\"workspaceId\":\"project\"}").body()).path("sessionId").asText();
            String base = "/v1/sessions/" + sessionId;
            assertEquals(202, send(server, "POST", base + "/runs", token,
                    "{\"commandId\":\"cmd-1\",\"runId\":\"run-1\","
                            + "\"kind\":\"PROMPT\",\"text\":\"wait\"}").statusCode());
            provider.awaitFirst();
            JsonNode cancellation = json(send(server, "POST", base + "/runs/run-1/cancel",
                    token, null).body());
            assertTrue(cancellation.path("cancelRequested").asBoolean());
            assertEquals(409, send(server, "POST", base + "/runs/run-1/inputs", token,
                    "{\"commandId\":\"cmd-input\",\"inputId\":\"input-1\","
                            + "\"targetRunId\":\"run-1\",\"mode\":\"STEER\","
                            + "\"text\":\"late\"}").statusCode());
            provider.completeFirst();
            server.sessions().find(sessionId).orElseThrow().settled("run-1")
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertTrue(json(send(server, "GET", base + "/runs/run-1", token,
                    null).body()).path("cancelRequested").asBoolean());
        }
    }

    private ApprovalView approvalEvent(SessionSubscription subscription) throws Exception {
        for (int index = 0; index < 20; index++) {
            var event = subscription.next(Duration.ofSeconds(5)).orElseThrow();
            if (event.type() == EventType.APPROVAL_CHANGED) {
                return mapper.treeToValue(event.data(), ApprovalView.class);
            }
        }
        throw new AssertionError("approval was not published");
    }

    private JcodeServer start(ModelProvider provider) throws Exception {
        return start(provider, Set.of());
    }

    private JcodeServer start(ModelProvider provider, Set<String> approvalTools) throws Exception {
        return start(provider, approvalTools, Set.of());
    }

    private JcodeServer start(ModelProvider provider, Set<String> approvalTools,
            Set<String> allowedOrigins) throws Exception {
        Path workspace = directory.resolve("workspace");
        Files.createDirectories(workspace);
        var config = new ServerConfig(0, directory.resolve("data"),
                directory.resolve("user-config"), Map.of("project", workspace),
                allowedOrigins, approvalTools, Duration.ofMinutes(5));
        return JcodeServer.start(config, new SessionRegistry(), exchange -> { },
                base -> borrowed(base, provider));
    }

    private static CodingAgentSessionOptions borrowed(
            CodingAgentSessionOptions base, ModelProvider provider) {
        return CodingAgentSessionOptions.builder(base.workingDirectory())
                .borrowedModels(new DefaultModels(List.of(provider)), Map.of())
                .settingsOverrides(SettingsOverrides.settings(CodingAgentSettings.builder()
                        .defaultModel(MODEL).build()))
                .inputDeliveryMode(InputDeliveryMode.RUN_SCOPED)
                .build();
    }

    private String token(JcodeServer server) throws Exception {
        return Files.readString(server.tokenFile()).strip();
    }

    private HttpResponse<String> send(
            JcodeServer server, String method, String path, String token, String body
    ) throws Exception {
        return send(server, method, path, token, body, "application/json");
    }

    private HttpResponse<String> send(
            JcodeServer server, String method, String path, String token,
            String body, String contentType
    ) throws Exception {
        var request = HttpRequest.newBuilder(server.endpoint().resolve(path))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token);
        if (body != null) {
            request.header("Content-Type", contentType);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(String source) throws Exception {
        return mapper.readTree(source);
    }

    private static String nextFrame(BufferedReader reader) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var frame = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        return frame.toString();
                    }
                    frame.append(line).append('\n');
                }
                throw new AssertionError("SSE stream ended without a control frame");
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        }).orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static String line(String frame, String prefix) {
        return frame.lines().filter(value -> value.startsWith(prefix))
                .findFirst().orElseThrow().substring(prefix.length());
    }

    private static class ReplyProvider implements ModelProvider {
        @Override public String id() { return "test"; }
        @Override public String name() { return "Test"; }
        @Override public Optional<URI> baseUrl() { return Optional.empty(); }
        @Override public ProviderAuth auth() { return ProviderAuth.of(true, ""); }
        @Override public List<Model> models() {
            return List.of(new Model("test", "scripted", "one", "One"));
        }
        @Override public boolean supports(ModelRef ref) { return MODEL.equals(ref); }
        @Override public AssistantMessageStream stream(ModelRequest request,
                CancellationSignal cancellation) {
            var answer = new Message.Assistant(List.of(new Content.Text("答复")),
                    StopReason.STOP, null, Usage.zero(), Instant.EPOCH, MODEL);
            var stream = new AssistantMessageStream();
            stream.push(new AssistantMessageEvent.Start(answer));
            stream.push(new AssistantMessageEvent.Done(StopReason.STOP, answer));
            return stream;
        }
    }

    private static final class PausedProvider extends ReplyProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private volatile AssistantMessageStream first;

        @Override public AssistantMessageStream stream(ModelRequest request,
                CancellationSignal cancellation) {
            if (calls.incrementAndGet() != 1) {
                return super.stream(request, cancellation);
            }
            var start = new Message.Assistant(List.of(), StopReason.STOP,
                    null, Usage.zero(), Instant.EPOCH, MODEL);
            first = new AssistantMessageStream();
            first.push(new AssistantMessageEvent.Start(start));
            started.countDown();
            return first;
        }

        private void completeFirst() throws InterruptedException {
            awaitFirst();
            var stream = first;
            assertNotNull(stream);
            var answer = new Message.Assistant(List.of(new Content.Text("first done")),
                    StopReason.STOP, null, Usage.zero(), Instant.EPOCH, MODEL);
            stream.push(new AssistantMessageEvent.Done(StopReason.STOP, answer));
        }

        private void awaitFirst() throws InterruptedException {
            assertTrue(started.await(5, TimeUnit.SECONDS));
        }
    }

    private final class ToolProvider extends ReplyProvider {
        private final AtomicInteger calls = new AtomicInteger();

        @Override public AssistantMessageStream stream(ModelRequest request,
                CancellationSignal cancellation) {
            if (calls.incrementAndGet() != 1) {
                return super.stream(request, cancellation);
            }
            var answer = new Message.Assistant(List.of(new Content.ToolCall("tool-1", "read",
                    mapper.createObjectNode().put("path", "allowed.txt"))),
                    StopReason.TOOL_CALL, null, Usage.zero(), Instant.EPOCH, MODEL);
            var stream = new AssistantMessageStream();
            stream.push(new AssistantMessageEvent.Start(answer));
            stream.push(new AssistantMessageEvent.Done(StopReason.TOOL_CALL, answer));
            return stream;
        }
    }
}
