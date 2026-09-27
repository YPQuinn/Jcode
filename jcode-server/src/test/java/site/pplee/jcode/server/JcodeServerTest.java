package site.pplee.jcode.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.codingagent.CodingAgentConfig;
import site.pplee.jcode.codingagent.InputDeliveryMode;
import site.pplee.jcode.protocol.RunCommand;
import site.pplee.jcode.protocol.RunKind;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JcodeServerTest {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "one");

    @TempDir
    Path directory;

    @Test
    void authenticatedHostStartsStopsAndKeepsItsTokenAcrossInstances() throws Exception {
        var config = config(directory.resolve("data"), 0);
        String token;
        String firstInstance;
        try (var server = JcodeServer.start(config)) {
            token = Files.readString(server.tokenFile()).strip();
            firstInstance = server.instanceId();
            assertEquals(InputDeliveryMode.RUN_SCOPED,
                    server.optionsForWorkspace("project").inputDeliveryMode());
            assertEquals(config.userConfigDirectory(), server.optionsForWorkspace("project")
                    .userConfigDirectory().orElseThrow());
            assertTrue(Files.isRegularFile(config.dataDirectory().resolve("runtime.json")));

            assertEquals(401, request(server.endpoint(), "GET", "/v1/capabilities",
                    null, null).statusCode());
            assertEquals(401, request(server.endpoint(), "GET", "/v1/capabilities",
                    "incorrect", null).statusCode());
            assertEquals(403, request(server.endpoint(), "GET", "/v1/capabilities",
                    token, "https://unlisted.example").statusCode());
            var capability = request(server.endpoint(), "GET", "/v1/capabilities",
                    token, "https://client.example");
            assertEquals(200, capability.statusCode());
            assertEquals(firstInstance, new ObjectMapper().readTree(capability.body())
                    .path("instanceId").asText());
            assertFalse(capability.body().contains(token));
            assertEquals(404, request(server.endpoint(), "GET", "/v1/unknown",
                    token, null).statusCode());

            var preflight = HttpRequest.newBuilder(server.endpoint().resolve("/v1/capabilities"))
                    .header("Origin", "https://client.example")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "Authorization")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(204, HttpClient.newHttpClient().send(preflight,
                    HttpResponse.BodyHandlers.discarding()).statusCode());

            assertEquals(202, request(server.endpoint(), "POST", "/v1/server/stop",
                    token, null).statusCode());
            awaitStop(server);
        }
        assertFalse(Files.exists(config.dataDirectory().resolve("runtime.json")));
        try (var restarted = JcodeServer.start(config)) {
            assertNotEquals(firstInstance, restarted.instanceId());
            assertEquals(token, Files.readString(restarted.tokenFile()).strip());
        }
    }

    @Test
    void directoryLockAndFailedPortBindReleaseOnlyTheFailedInstance() throws Exception {
        var config = config(directory.resolve("first"), 0);
        try (var first = JcodeServer.start(config)) {
            assertThrows(IOException.class, () -> JcodeServer.start(config));
            String token = Files.readString(first.tokenFile()).strip();
            assertEquals(200, request(first.endpoint(), "GET", "/v1/capabilities",
                    token, null).statusCode());

            var conflicting = config(directory.resolve("second"), first.endpoint().getPort());
            assertThrows(IOException.class, () -> JcodeServer.start(conflicting));
            assertFalse(Files.exists(conflicting.dataDirectory().resolve("runtime.json")));
            try (var afterFailure = JcodeServer.start(config(conflicting.dataDirectory(), 0))) {
                assertNotEquals(first.instanceId(), afterFailure.instanceId());
            }
        }
    }

    @Test
    void idleStopRejectsAnActiveRunAndSucceedsAfterSettlement() throws Exception {
        var config = config(directory.resolve("data"), 0);
        try (var server = JcodeServer.start(config)) {
            var model = new PausedModel();
            var managed = server.sessions().create(new CodingAgentConfig(
                    directory, MODEL, model, new ObjectMapper(),
                    null, null, null, null, null, null, null, null,
                    null, null, null, Map.of(), null, InputDeliveryMode.RUN_SCOPED),
                    directory.resolve("sessions"));
            managed.start(new RunCommand("command-1", "run-1", RunKind.PROMPT,
                    "wait", null));
            String token = Files.readString(server.tokenFile()).strip();
            assertEquals(409, request(server.endpoint(), "POST", "/v1/server/stop",
                    token, null).statusCode());

            model.complete();
            managed.settled("run-1").toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(202, request(server.endpoint(), "POST", "/v1/server/stop",
                    token, null).statusCode());
            awaitStop(server);
        }
    }

    private ServerConfig config(Path dataDirectory, int port) throws IOException {
        Path workspace = directory.resolve("workspace");
        Files.createDirectories(workspace);
        return new ServerConfig(port, dataDirectory, directory.resolve("user-config"),
                Map.of("project", workspace), Set.of("https://client.example"),
                Set.of(), Duration.ofMinutes(5));
    }

    private static HttpResponse<String> request(URI endpoint, String method, String path,
            String token, String origin) throws Exception {
        var builder = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofSeconds(5));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void awaitStop(JcodeServer server) {
        CompletableFuture.runAsync(() -> {
            try {
                server.awaitTermination();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }).orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static final class PausedModel implements ModelClient {
        private final AssistantMessageStream stream = new AssistantMessageStream();

        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            var start = new Message.Assistant(java.util.List.of(), StopReason.STOP,
                    null, Usage.zero(), Instant.EPOCH, MODEL);
            stream.push(new AssistantMessageEvent.Start(start));
            return stream;
        }

        private void complete() {
            var done = new Message.Assistant(java.util.List.of(new Content.Text("done")),
                    StopReason.STOP, null, Usage.zero(), Instant.EPOCH, MODEL);
            stream.push(new AssistantMessageEvent.Done(StopReason.STOP, done));
        }
    }
}
