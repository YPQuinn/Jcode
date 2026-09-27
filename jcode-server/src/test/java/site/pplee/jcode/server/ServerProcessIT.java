package site.pplee.jcode.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.StandardWatchEventKinds;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the packaged JAR as distinct operating-system processes. */
class ServerProcessIT {
    @TempDir
    Path directory;

    @Test
    void terminationSignalRunsCleanupAndAllowsRestart() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Path data = directory.resolve("data");
        Path config = writeConfig("signal.json", data, workspace, 0);
        Process first = launch(config);
        try {
            var initial = ready(first);
            assertTrue(Files.exists(data.resolve("runtime.json")));
            first.destroy();
            assertTrue(first.waitFor(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(data.resolve("runtime.json")));

            Process restarted = launch(config);
            try {
                var next = ready(restarted);
                assertNotEquals(initial.instanceId(), next.instanceId());
                assertEquals(202, stop(next.endpoint(),
                        Files.readString(data.resolve("service.token")).strip()).statusCode());
                assertTrue(restarted.waitFor(5, TimeUnit.SECONDS));
            } finally {
                destroy(restarted);
            }
        } finally {
            destroy(first);
        }
    }

    @Test
    void simultaneousStartsOfOneDataDirectoryProduceOneOwner() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Path data = directory.resolve("data");
        Path config = writeConfig("simultaneous.json", data, workspace, 0);
        Process first = launch(config);
        Process second = launch(config);
        try {
            String firstLine = firstLine(first);
            String secondLine = firstLine(second);
            boolean firstReady = firstLine.startsWith("JCODE_SERVER_READY ");
            boolean secondReady = secondLine.startsWith("JCODE_SERVER_READY ");
            assertNotEquals(firstReady, secondReady,
                    "exactly one process must own the data directory");

            Process winner = firstReady ? first : second;
            Process loser = firstReady ? second : first;
            assertTrue(loser.waitFor(5, TimeUnit.SECONDS));
            assertNotEquals(0, loser.exitValue());
            Ready ready = parseReady(firstReady ? firstLine : secondLine);
            String token = Files.readString(data.resolve("service.token")).strip();
            assertEquals(200, capabilities(ready.endpoint(), token).statusCode());
            assertEquals(202, stop(ready.endpoint(), token).statusCode());
            assertTrue(winner.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, winner.exitValue());
        } finally {
            destroy(first);
            destroy(second);
        }
    }

    @Test
    void packagedServiceExcludesAnotherProcessAndReleasesOwnershipOnExit()
            throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Path data = directory.resolve("data");
        Path config = writeConfig("first.json", data, workspace, 0);
        Process first = launch(config);
        try {
            var ready = ready(first);
            String token = Files.readString(data.resolve("service.token")).strip();
            assertEquals(200, capabilities(ready.endpoint(), token).statusCode());
            assertEquals(ready.instanceId(), new ObjectMapper().readTree(
                    Files.readString(data.resolve("runtime.json"))).path("instanceId").asText());

            Process second = launch(config);
            try {
                assertTrue(second.waitFor(5, TimeUnit.SECONDS));
                assertNotEquals(0, second.exitValue());
            } finally {
                destroy(second);
            }
            assertEquals(200, capabilities(ready.endpoint(), token).statusCode());

            Path otherData = directory.resolve("other-data");
            Process portConflict = launch(writeConfig("conflict.json", otherData,
                    workspace, ready.endpoint().getPort()));
            try {
                assertTrue(portConflict.waitFor(5, TimeUnit.SECONDS));
                assertNotEquals(0, portConflict.exitValue());
            } finally {
                destroy(portConflict);
            }
            assertFalse(Files.exists(otherData.resolve("runtime.json")));

            assertEquals(202, stop(ready.endpoint(), token).statusCode());
            assertTrue(first.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, first.exitValue());
            assertFalse(Files.exists(data.resolve("runtime.json")));

            Process restarted = launch(config);
            try {
                var next = ready(restarted);
                assertNotEquals(ready.instanceId(), next.instanceId());
                assertEquals(token, Files.readString(data.resolve("service.token")).strip());
                assertEquals(202, stop(next.endpoint(), token).statusCode());
                assertTrue(restarted.waitFor(5, TimeUnit.SECONDS));
                assertEquals(0, restarted.exitValue());
            } finally {
                destroy(restarted);
            }

            Process afterBindFailure = launch(writeConfig("other.json", otherData,
                    workspace, 0));
            try {
                var next = ready(afterBindFailure);
                assertEquals(202, stop(next.endpoint(),
                        Files.readString(otherData.resolve("service.token")).strip()).statusCode());
                assertTrue(afterBindFailure.waitFor(5, TimeUnit.SECONDS));
            } finally {
                destroy(afterBindFailure);
            }
        } finally {
            destroy(first);
        }
    }

    @Test
    void failedSameJvmStartDoesNotReleaseTheOwnersProcessLock() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Path data = directory.resolve("data");
        Path configFile = writeConfig("owner.json", data, workspace, 0);
        var config = ServerConfig.load(configFile, new ObjectMapper());
        try (var owner = JcodeServer.start(config)) {
            assertThrows(IOException.class, () -> JcodeServer.start(config));
            Process competitor = launch(configFile);
            try {
                assertTrue(competitor.waitFor(5, TimeUnit.SECONDS));
                assertNotEquals(0, competitor.exitValue());
            } finally {
                destroy(competitor);
            }
        }

        Process successor = launch(configFile);
        try {
            var ready = ready(successor);
            assertEquals(202, stop(ready.endpoint(),
                    Files.readString(data.resolve("service.token")).strip()).statusCode());
            assertTrue(successor.waitFor(5, TimeUnit.SECONDS));
        } finally {
            destroy(successor);
        }
    }

    @Test
    void unreadSseSocketDoesNotBlockQueriesOrServiceShutdown() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Path data = directory.resolve("data");
        Path config = writeConfig("timeout.json", data, workspace, 0);
        Path trigger = directory.resolve("flood.trigger");
        Path done = directory.resolve("flood.done");
        Process fixture = launchTimeoutFixture(config, trigger, done);
        try {
            String line = firstLine(fixture);
            assertNotNull(line);
            assertTrue(line.startsWith("TEST_SERVER_READY "), line);
            String[] parts = line.split(" ");
            URI endpoint = URI.create(parts[1].substring("endpoint=".length()));
            String sessionId = parts[2].substring("sessionId=".length());
            String after = parts[3].substring("after=".length());
            String token = Files.readString(data.resolve("service.token")).strip();
            try (var socket = new Socket("127.0.0.1", endpoint.getPort())) {
                socket.setReceiveBufferSize(1_024);
                socket.setSoTimeout(5_000);
                String request = "GET /v1/sessions/" + sessionId + "/events?after="
                        + after + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                        + "Authorization: Bearer " + token + "\r\n\r\n";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                assertTrue(readHeaders(socket).startsWith("HTTP/1.1 200"));

                try (var watch = FileSystems.getDefault().newWatchService()) {
                    directory.register(watch, StandardWatchEventKinds.ENTRY_CREATE);
                    Files.createFile(trigger);
                    while (!Files.exists(done)) {
                        var key = watch.poll(20, TimeUnit.SECONDS);
                        assertNotNull(key, "model flood did not settle");
                        key.reset();
                    }
                }
                assertEquals(200, capabilities(endpoint, token).statusCode());
                // The response deadline must release this connection while the service stays up.
                boolean ended = false;
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                byte[] received = new byte[16_384];
                socket.setSoTimeout(1_000);
                while (System.nanoTime() < deadline) {
                    try {
                        if (socket.getInputStream().read(received) == -1) {
                            ended = true;
                            break;
                        }
                    } catch (SocketTimeoutException waiting) {
                        // Keep waiting within the bounded deadline for the peer to close.
                    }
                }
                assertTrue(ended, "unread SSE response must close before service shutdown");
                assertEquals(200, capabilities(endpoint, token).statusCode());
                assertEquals(202, stop(endpoint, token).statusCode());
                assertTrue(fixture.waitFor(10, TimeUnit.SECONDS),
                        "service must close a blocked SSE exchange during shutdown");
                assertEquals(0, fixture.exitValue());
            }
        } finally {
            destroy(fixture);
        }
    }

    private static Process launchTimeoutFixture(Path config, Path trigger, Path done)
            throws Exception {
        String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path classes = Path.of(ServerTimeoutFixture.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        String classpath = classes + java.io.File.pathSeparator
                + System.getProperty("jcode.server.jar");
        return new ProcessBuilder(javaBinary,
                "-Dsun.net.httpserver.maxRspTime=1",
                "-Dsun.net.httpserver.timerMillis=100",
                "--add-modules", "jdk.httpserver", "-cp", classpath,
                ServerTimeoutFixture.class.getName(), config.toString(),
                trigger.toString(), done.toString()).start();
    }

    private static String readHeaders(Socket socket) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        int previous = -1;
        int beforePrevious = -1;
        int beforeThat = -1;
        while (bytes.size() < 8_192) {
            int current = socket.getInputStream().read();
            if (current == -1) {
                throw new IOException("SSE response ended before headers");
            }
            bytes.write(current);
            if (beforeThat == '\r' && beforePrevious == '\n'
                    && previous == '\r' && current == '\n') {
                return bytes.toString(StandardCharsets.US_ASCII);
            }
            beforeThat = beforePrevious;
            beforePrevious = previous;
            previous = current;
        }
        throw new IOException("SSE response headers exceed 8 KiB");
    }

    private Path writeConfig(String name, Path data, Path workspace, int port)
            throws IOException {
        Path file = directory.resolve(name);
        var settings = Map.of(
                "port", port,
                "dataDirectory", data.toString(),
                "userConfigDirectory", directory.resolve("user-config").toString(),
                "workspaces", Map.of("project", workspace.toString()),
                "allowedOrigins", java.util.List.of(),
                "approvalTools", java.util.List.of(),
                "approvalTimeoutSeconds", 300);
        new ObjectMapper().writeValue(file.toFile(), settings);
        return file;
    }

    private static Process launch(Path config) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "--add-modules", "jdk.httpserver", "-jar",
                System.getProperty("jcode.server.jar"), "--config", config.toString())
                .redirectErrorStream(true).start();
    }

    private static Ready ready(Process process) throws Exception {
        return parseReady(firstLine(process));
    }

    private static String firstLine(Process process) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return process.inputReader().readLine();
            } catch (IOException failure) {
                throw new RuntimeException(failure);
            }
        }).orTimeout(10, TimeUnit.SECONDS).join();
    }

    private static Ready parseReady(String line) {
        assertNotNull(line, "service exited before readiness");
        assertTrue(line.startsWith("JCODE_SERVER_READY "), line);
        String[] parts = line.split(" ");
        return new Ready(URI.create(parts[1].substring("endpoint=".length())),
                parts[2].substring("instanceId=".length()));
    }

    private static HttpResponse<String> capabilities(URI endpoint, String token)
            throws Exception {
        return send(endpoint.resolve("/v1/capabilities"), "GET", token);
    }

    private static HttpResponse<String> stop(URI endpoint, String token) throws Exception {
        return send(endpoint.resolve("/v1/server/stop"), "POST", token);
    }

    private static HttpResponse<String> send(URI uri, String method, String token)
            throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void destroy(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(2, TimeUnit.SECONDS));
            }
        }
    }

    private record Ready(URI endpoint, String instanceId) { }
}
