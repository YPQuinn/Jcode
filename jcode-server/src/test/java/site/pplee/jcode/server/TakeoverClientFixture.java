package site.pplee.jcode.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Two real client processes; neither has access to the service's Java objects. */
public final class TakeoverClientFixture {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private TakeoverClientFixture() { }

    public static void main(String[] args) throws Exception {
        URI endpoint = URI.create(args[1]);
        String token = Files.readString(Path.of(args[2])).strip();
        if ("first".equals(args[0])) {
            first(endpoint, token);
        } else if ("second".equals(args[0])) {
            second(endpoint, token, args[3], args[4]);
        } else {
            throw new IllegalArgumentException("unknown client phase");
        }
    }

    private static void first(URI endpoint, String token) throws Exception {
        JsonNode created = json(send(endpoint, token, "POST", "/v1/sessions",
                Map.of("workspaceId", "project")), 201);
        String sessionId = created.path("sessionId").asText();
        String base = "/v1/sessions/" + sessionId;
        JsonNode snapshot = json(send(endpoint, token, "GET", base + "/snapshot", null), 200);
        String cursor = cursor(snapshot.path("cursor"));
        var subscription = HttpRequest.newBuilder(endpoint.resolve(base + "/events?after=" + cursor))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        var stream = HTTP.send(subscription, HttpResponse.BodyHandlers.ofInputStream());
        require(stream.statusCode() == 200, "client A could not subscribe");

        JsonNode run = json(send(endpoint, token, "POST", base + "/runs", Map.of(
                "commandId", "command-1", "runId", "run-1", "kind", "PROMPT",
                "text", "run the approved tool")), 202);
        require("run-1".equals(run.path("runId").asText()), "run identity changed");
        System.out.println("CLIENT_A_READY sessionId=" + sessionId
                + " runId=run-1 fileRef=" + created.path("fileRef").asText()
                + " pid=" + ProcessHandle.current().pid());
        System.out.flush();
        new CountDownLatch(1).await();
    }

    private static void second(
            URI endpoint, String token, String sessionId, String expectedInstanceId
    ) throws Exception {
        JsonNode capabilities = json(send(endpoint, token, "GET", "/v1/capabilities", null), 200);
        require(expectedInstanceId.equals(capabilities.path("instanceId").asText()),
                "service instance changed during takeover");
        String base = "/v1/sessions/" + sessionId;
        JsonNode snapshot = json(send(endpoint, token, "GET", base + "/snapshot", null), 200);
        require(snapshot.path("runs").size() == 1, "original Run was not retained");
        require("run-1".equals(snapshot.path("runs").get(0).path("runId").asText()),
                "client B saw a different Run");
        require(snapshot.path("approvals").size() == 1
                && "PENDING".equals(snapshot.path("approvals").get(0).path("status").asText()),
                "offline approval was not pending");
        JsonNode approval = snapshot.path("approvals").get(0);
        String cursor = cursor(snapshot.path("cursor"));
        var request = HttpRequest.newBuilder(endpoint.resolve(base + "/events?after=" + cursor))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        var stream = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        require(stream.statusCode() == 200, "client B could not resume events");
        try (var reader = new BufferedReader(new InputStreamReader(
                stream.body(), StandardCharsets.UTF_8))) {
            json(send(endpoint, token, "POST", base + "/runs/run-1/inputs", Map.of(
                    "commandId", "input-command", "inputId", "input-1",
                    "targetRunId", "run-1", "mode", "FOLLOW_UP",
                    "text", "summarize after the tool")), 202);
            String approvalPath = base + "/approvals/" + approval.path("approvalId").asText()
                    + "/resolve";
            var allow = Map.of("approvalId", approval.path("approvalId").asText(),
                    "toolCallId", approval.path("toolCallId").asText(),
                    "requestDigest", approval.path("requestDigest").asText(),
                    "decision", "ALLOW");
            require("ALLOWED".equals(json(send(endpoint, token, "POST", approvalPath, allow),
                    200).path("status").asText()), "approval was not allowed");
            json(send(endpoint, token, "POST", approvalPath, allow), 200);

            boolean completed = false;
            for (int index = 0; index < 80; index++) {
                JsonNode event = nextEvent(reader);
                if ("RUN_CHANGED".equals(event.path("type").asText())
                        && "COMPLETED".equals(event.path("data").path("status").asText())) {
                    completed = true;
                    break;
                }
            }
            require(completed, "original Run did not complete on resumed SSE");
        }

        JsonNode finalSnapshot = json(send(endpoint, token, "GET", base + "/snapshot", null), 200);
        require(finalSnapshot.path("runs").size() == 1
                && "COMPLETED".equals(finalSnapshot.path("runs").get(0)
                        .path("status").asText()), "takeover created another Run");
        JsonNode input = json(send(endpoint, token, "GET", base + "/inputs/input-1", null), 200);
        require("APPLIED_TO_CONTEXT".equals(input.path("status").asText())
                && input.path("entryId").isTextual(), "follow-up did not enter history");
        JsonNode history = json(send(endpoint, token, "GET", base + "/history?limit=100", null), 200);
        String entryId = input.path("entryId").asText();
        boolean inHistory = false;
        for (JsonNode entry : history.path("entries")) {
            inHistory |= entryId.equals(entry.path("entryId").asText());
        }
        require(inHistory, "input entry is absent from persisted history");
        System.out.println("CLIENT_B_DONE sessionId=" + sessionId
                + " runId=run-1 entryId=" + entryId
                + " epoch=" + finalSnapshot.path("cursor").path("epoch").asText()
                + " pid=" + ProcessHandle.current().pid());
        System.out.flush();
    }

    private static JsonNode nextEvent(BufferedReader reader) throws Exception {
        String line;
        String eventName = null;
        String data = null;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("event: ")) {
                eventName = line.substring(7);
            } else if (line.startsWith("data: ")) {
                data = line.substring(6);
            } else if (line.isEmpty()) {
                if ("stream.control".equals(eventName)) {
                    throw new AssertionError("event stream requested resynchronization");
                }
                if ("session.event".equals(eventName) && data != null) {
                    return MAPPER.readTree(data);
                }
                eventName = null;
                data = null;
            }
        }
        throw new AssertionError("event stream ended before Run completion");
    }

    private static String cursor(JsonNode value) {
        return value.path("epoch").asText() + ":" + value.path("seq").asLong();
    }

    private static HttpResponse<String> send(
            URI endpoint, String token, String method, String path, Object body
    ) throws Exception {
        var builder = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response, int expected) throws Exception {
        require(response.statusCode() == expected,
                "HTTP " + response.statusCode() + " instead of " + expected + ": " + response.body());
        return MAPPER.readTree(response.body());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
