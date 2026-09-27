package site.pplee.jcode.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Three separate JVMs exercise takeover, approval, and crash-only history recovery. */
class TakeoverProcessIT {
    @TempDir
    Path directory;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aClientExitsAndAnotherCompletesTheSameRunThenHistorySurvivesServerCrash()
            throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "test fixture needs a local shell");
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Path data = directory.resolve("data");
        Path config = writeConfig("server.json", workspace, data, 60);
        Path toolGate = directory.resolve("tool.gate");
        Path observerReady = directory.resolve("observer.ready");
        Path approvalReady = directory.resolve("approval.ready");
        Process server = launch(TakeoverServerFixture.class, config, toolGate,
                observerReady, approvalReady);
        Process first = null;
        Process second = null;
        Process restarted = null;
        try {
            Map<String, String> service = ready(server, "TAKEOVER_SERVER_READY");
            URI endpoint = URI.create(service.get("endpoint"));
            String instanceId = service.get("instanceId");
            assertEquals(server.pid(), Long.parseLong(service.get("pid")));
            String token = Files.readString(data.resolve("service.token")).strip();

            first = launch(TakeoverClientFixture.class, "first", endpoint,
                    data.resolve("service.token"));
            Map<String, String> clientA = ready(first, "CLIENT_A_READY");
            String sessionId = clientA.get("sessionId");
            assertEquals("run-1", clientA.get("runId"));
            assertEquals(first.pid(), Long.parseLong(clientA.get("pid")));
            awaitFile(observerReady);
            first.destroyForcibly();
            assertTrue(first.waitFor(5, TimeUnit.SECONDS));
            assertTrue(server.isAlive(), "client A must not own the service lifecycle");

            Files.createFile(toolGate);
            awaitFile(approvalReady);
            assertTrue(server.isAlive(), "approval is pending with no external client");
            second = launch(TakeoverClientFixture.class, "second", endpoint,
                    data.resolve("service.token"), sessionId, instanceId);
            Map<String, String> clientB = ready(second, "CLIENT_B_DONE");
            assertEquals(sessionId, clientB.get("sessionId"));
            assertEquals("run-1", clientB.get("runId"));
            assertEquals(second.pid(), Long.parseLong(clientB.get("pid")));
            assertTrue(second.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, second.exitValue());
            Path effects = workspace.resolve("effects.txt");
            assertEquals(List.of("one execution"), Files.readAllLines(effects),
                    "the approved tool must execute exactly once");
            assertEquals(200, request(endpoint, token, "GET", "/v1/capabilities", null)
                    .statusCode());

            server.destroyForcibly();
            assertTrue(server.waitFor(5, TimeUnit.SECONDS));
            restarted = launch(TakeoverServerFixture.class, config, toolGate,
                    observerReady, approvalReady);
            Map<String, String> newService = ready(restarted, "TAKEOVER_SERVER_READY");
            URI newEndpoint = URI.create(newService.get("endpoint"));
            assertNotEquals(instanceId, newService.get("instanceId"));
            assertEquals(token, Files.readString(data.resolve("service.token")).strip());
            assertEquals(0, json(request(newEndpoint, token, "GET", "/v1/sessions", null),
                    200).size(), "the old managed owner must not revive");

            JsonNode opened = json(request(newEndpoint, token, "POST", "/v1/sessions/open",
                    Map.of("workspaceId", "project", "fileRef", clientA.get("fileRef"))), 200);
            assertEquals(sessionId, opened.path("sessionId").asText());
            String base = "/v1/sessions/" + sessionId;
            JsonNode snapshot = json(request(newEndpoint, token, "GET", base + "/snapshot",
                    null), 200);
            assertNotEquals(clientB.get("epoch"), snapshot.path("cursor")
                    .path("epoch").asText());
            assertEquals(0, snapshot.path("runs").size());
            assertEquals(0, snapshot.path("inputs").size());
            assertEquals(0, snapshot.path("approvals").size());
            JsonNode history = json(request(newEndpoint, token, "GET", base
                    + "/history?limit=100", null), 200);
            assertTrue(history.path("entries").size() > 0);
            boolean inputInHistory = false;
            for (JsonNode entry : history.path("entries")) {
                inputInHistory |= clientB.get("entryId")
                        .equals(entry.path("entryId").asText());
            }
            assertTrue(inputInHistory, "the applied input must remain in JSONL history");
            assertEquals(404, request(newEndpoint, token, "GET", base + "/runs/run-1", null)
                    .statusCode());
            assertEquals(404, request(newEndpoint, token, "GET", base + "/inputs/input-1", null)
                    .statusCode());
            assertEquals(List.of("one execution"), Files.readAllLines(effects),
                    "opening saved history must not execute tools again");
            assertEquals(202, request(newEndpoint, token, "POST", "/v1/server/stop", null)
                    .statusCode());
            assertTrue(restarted.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, restarted.exitValue());
        } finally {
            destroy(first);
            destroy(second);
            destroy(server);
            destroy(restarted);
        }
    }

    @Test
    void offlineApprovalCanBeCancelledOrExpireWithoutExecutingItsTool() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "test fixture needs a local shell");
        for (String outcome : List.of("cancel", "expire")) {
            Path caseDirectory = Files.createDirectory(directory.resolve(outcome));
            Path workspace = Files.createDirectory(caseDirectory.resolve("workspace"));
            Path data = caseDirectory.resolve("data");
            Path config = writeConfig(outcome + "-server.json", workspace, data,
                    "expire".equals(outcome) ? 1 : 60);
            Path gate = caseDirectory.resolve("tool.gate");
            Path observerReady = caseDirectory.resolve("observer.ready");
            Path approvalReady = caseDirectory.resolve("approval.ready");
            Process server = launch(TakeoverServerFixture.class, config, gate,
                    observerReady, approvalReady);
            Process first = null;
            try {
                URI endpoint = URI.create(ready(server, "TAKEOVER_SERVER_READY").get("endpoint"));
                String token = Files.readString(data.resolve("service.token")).strip();
                first = launch(TakeoverClientFixture.class, "first", endpoint,
                        data.resolve("service.token"));
                String sessionId = ready(first, "CLIENT_A_READY").get("sessionId");
                awaitFile(observerReady);
                first.destroyForcibly();
                assertTrue(first.waitFor(5, TimeUnit.SECONDS));
                Files.createFile(gate);
                awaitFile(approvalReady);

                String base = "/v1/sessions/" + sessionId;
                if ("cancel".equals(outcome)) {
                    JsonNode requested = json(request(endpoint, token, "POST", base
                            + "/runs/run-1/cancel", null), 200);
                    assertTrue(requested.path("cancelRequested").asBoolean());
                }
                awaitFile(caseDirectory.resolve("approval.terminal"));
                awaitFile(caseDirectory.resolve("run.terminal"));
                JsonNode snapshot = json(request(endpoint, token, "GET", base + "/snapshot",
                        null), 200);
                assertEquals(1, snapshot.path("runs").size());
                assertTrue(List.of("COMPLETED", "FAILED", "CANCELLED").contains(
                        snapshot.path("runs").get(0).path("status").asText()));
                assertEquals("cancel".equals(outcome) ? "CANCELLED" : "EXPIRED",
                        snapshot.path("approvals").get(0).path("status").asText());
                assertFalse(Files.exists(workspace.resolve("effects.txt")),
                        "an unapproved tool must not execute");
                assertEquals(202, request(endpoint, token, "POST", "/v1/server/stop", null)
                        .statusCode());
                assertTrue(server.waitFor(5, TimeUnit.SECONDS));
                assertEquals(0, server.exitValue());
            } finally {
                destroy(first);
                destroy(server);
            }
        }
    }

    private Path writeConfig(String name, Path workspace, Path data, int timeoutSeconds)
            throws IOException {
        Path file = directory.resolve(name);
        mapper.writeValue(file.toFile(), Map.of(
                "port", 0,
                "dataDirectory", data.toString(),
                "userConfigDirectory", directory.resolve("user-config").toString(),
                "workspaces", Map.of("project", workspace.toString()),
                "allowedOrigins", List.of(),
                "approvalTools", List.of("bash"),
                "approvalTimeoutSeconds", timeoutSeconds));
        return file;
    }

    private static Process launch(Class<?> entry, Object... args) throws Exception {
        String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(entry.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toString();
        String classpath = classes + java.io.File.pathSeparator
                + System.getProperty("jcode.server.jar");
        var command = new java.util.ArrayList<String>();
        command.addAll(List.of(javaBinary, "--add-modules", "jdk.httpserver", "-cp", classpath,
                entry.getName()));
        for (Object arg : args) {
            command.add(arg.toString());
        }
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private static Map<String, String> ready(Process process, String prefix) {
        String line = CompletableFuture.supplyAsync(() -> {
            try {
                var precedingOutput = new StringBuilder();
                String next;
                while ((next = process.inputReader().readLine()) != null) {
                    if (next.startsWith(prefix + " ")) {
                        return next;
                    }
                    if (precedingOutput.length() < 4_096) {
                        precedingOutput.append(next).append('\n');
                    }
                }
                throw new AssertionError(prefix + " not reported by process:\n" + precedingOutput);
            } catch (IOException failure) {
                throw new RuntimeException(failure);
            }
        }).orTimeout(20, TimeUnit.SECONDS).join();
        var values = new HashMap<String, String>();
        for (String field : line.substring(prefix.length() + 1).split(" ")) {
            int separator = field.indexOf('=');
            values.put(field.substring(0, separator), field.substring(separator + 1));
        }
        return values;
    }

    private static void awaitFile(Path file) throws Exception {
        try (var watcher = FileSystems.getDefault().newWatchService()) {
            file.getParent().register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (!Files.exists(file)) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0, "fixture did not create " + file.getFileName());
                var key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                assertNotNull(key, "fixture did not create " + file.getFileName());
                key.reset();
            }
        }
    }

    private HttpResponse<String> request(
            URI endpoint, String token, String method, String path, Object body
    ) throws Exception {
        var builder = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response, int expected) throws Exception {
        assertEquals(expected, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private static void destroy(Process process) throws InterruptedException {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }
}
