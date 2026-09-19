package site.pplee.jcode.codingagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.codingagent.event.CodingAgentEvent;
import site.pplee.jcode.codingagent.support.ScriptedModelClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CodingAgentIntegrationTest {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "model");
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    @TempDir
    Path directory;

    @Test
    void completesTextReadToolResultAndFinalAssistantFlowWithIsolatedEvents() throws Exception {
        Files.writeString(directory.resolve("fixture.txt"), "expected content\n");
        var events = new CopyOnWriteArrayList<CodingAgentEvent>();
        var sessionReference = new AtomicReference<CodingAgentSession>();
        var client = new ScriptedModelClient(
                request -> {
                    assertEquals("read", request.tools().getFirst().name());
                    assertTrue(request.systemPrompt().contains(directory.toAbsolutePath().normalize().toString()));
                    var args = new ObjectMapper().createObjectNode().put("path", "fixture.txt");
                    return assistant(List.of(new Content.ToolCall("call-1", "read", args)), StopReason.TOOL_CALL);
                },
                request -> {
                    var callMessage = assertInstanceOf(Message.Assistant.class, request.messages().get(1));
                    var call = assertInstanceOf(Content.ToolCall.class, callMessage.content().getFirst());
                    assertEquals("fixture.txt", call.arguments().get("path").textValue());
                    var result = assertInstanceOf(Message.ToolResultMessage.class, request.messages().get(2));
                    assertEquals("expected content\n", assertInstanceOf(Content.Text.class,
                            result.content().getFirst()).text());
                    return assistant(List.of(new Content.Text("done")), StopReason.STOP);
                });
        var config = config(client, event -> {
            events.add(event);
            if (event instanceof CodingAgentEvent.RuntimeEvent runtime
                    && runtime.event() instanceof AgentEvent.ToolStarted started) {
                assertTrue(sessionReference.get().state().pendingToolCalls().contains("call-1"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) started.call().arguments())
                        .put("path", "mutated-by-observer.txt");
            }
            return CompletableFuture.completedStage(null);
        });

        CodingAgentRunResult result;
        try (var session = new CodingAgentSession(config)) {
            sessionReference.set(session);
            result = session.prompt("read the fixture").toCompletableFuture().join();
            assertFalse(session.isRunning());
            assertEquals("done", assertInstanceOf(Content.Text.class,
                    result.finalMessage().content().getFirst()).text());
        }

        assertEquals(2, client.requests().size());
        assertEquals(1, events.stream().filter(CodingAgentEvent.RunCompleted.class::isInstance).count());
        assertTrue(events.getLast() instanceof CodingAgentEvent.RunCompleted);
        assertFalse(events.stream()
                .filter(CodingAgentEvent.RuntimeEvent.class::isInstance)
                .map(CodingAgentEvent.RuntimeEvent.class::cast)
                .anyMatch(event -> event.event() instanceof AgentEvent.AgentCompleted));
        assertEquals(List.of(
                        "AgentStarted", "TurnStarted", "MessageStarted", "MessageCompleted",
                        "MessageStarted", "MessageCompleted", "ToolStarted", "ToolCompleted",
                        "MessageStarted", "MessageCompleted", "TurnCompleted", "TurnStarted",
                        "MessageStarted", "MessageCompleted", "TurnCompleted", "RunCompleted"),
                events.stream().map(CodingAgentIntegrationTest::eventName).toList());

        var completed = (CodingAgentEvent.RunCompleted) events.getLast();
        var completedCall = completed.result().newMessages().stream()
                .filter(StandardAgentMessage.class::isInstance)
                .map(StandardAgentMessage.class::cast)
                .map(StandardAgentMessage::message)
                .filter(Message.Assistant.class::isInstance)
                .map(Message.Assistant.class::cast)
                .flatMap(message -> message.content().stream())
                .filter(Content.ToolCall.class::isInstance)
                .map(Content.ToolCall.class::cast)
                .findFirst().orElseThrow();
        ((com.fasterxml.jackson.databind.node.ObjectNode) completedCall.arguments())
                .put("path", "mutated-completion.txt");

        var resultCall = result.newMessages().stream()
                .filter(StandardAgentMessage.class::isInstance)
                .map(StandardAgentMessage.class::cast)
                .map(StandardAgentMessage::message)
                .filter(Message.Assistant.class::isInstance)
                .map(Message.Assistant.class::cast)
                .flatMap(message -> message.content().stream())
                .filter(Content.ToolCall.class::isInstance)
                .map(Content.ToolCall.class::cast)
                .findFirst().orElseThrow();
        assertEquals("fixture.txt", resultCall.arguments().get("path").textValue());
    }

    @Test
    void modelCanRecoverAfterReadFailure() {
        var client = new ScriptedModelClient(
                request -> assistant(List.of(new Content.ToolCall(
                        "missing", "read",
                        new ObjectMapper().createObjectNode().put("path", "missing.txt"))),
                        StopReason.TOOL_CALL),
                request -> {
                    var result = assertInstanceOf(Message.ToolResultMessage.class,
                            request.messages().getLast());
                    assertTrue(result.error());
                    assertTrue(assertInstanceOf(Content.Text.class,
                            result.content().getFirst()).text().contains("does not exist"));
                    return assistant(List.of(new Content.Text("recovered")), StopReason.STOP);
                });

        try (var session = new CodingAgentSession(config(client, null))) {
            var result = session.prompt("read missing").toCompletableFuture().join();
            assertEquals("recovered", assertInstanceOf(Content.Text.class,
                    result.finalMessage().content().getFirst()).text());
        }
    }

    @Test
    void supportedImageBecomesToolResultInNextModelRequest() throws Exception {
        byte[] png = new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1
        };
        Files.write(directory.resolve("pixel.png"), png);
        var client = new ScriptedModelClient(
                request -> assistant(List.of(new Content.ToolCall(
                        "image", "read",
                        new ObjectMapper().createObjectNode().put("path", "pixel.png"))),
                        StopReason.TOOL_CALL),
                request -> {
                    var result = assertInstanceOf(Message.ToolResultMessage.class,
                            request.messages().getLast());
                    var image = assertInstanceOf(Content.Image.class, result.content().getFirst());
                    assertEquals("image/png", image.mediaType());
                    assertFalse(result.error());
                    return assistant(List.of(new Content.Text("image received")), StopReason.STOP);
                });

        try (var session = new CodingAgentSession(config(client, null))) {
            assertEquals("image received", assertInstanceOf(Content.Text.class,
                    session.prompt("read image").toCompletableFuture().join()
                            .finalMessage().content().getFirst()).text());
        }
    }

    private CodingAgentConfig config(
            ScriptedModelClient client,
            site.pplee.jcode.codingagent.event.CodingAgentEventSink sink
    ) {
        return new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                ThinkingLevel.PROVIDER_DEFAULT, ModelRequestOptions.defaults(),
                QueueMode.ONE_AT_A_TIME, QueueMode.ONE_AT_A_TIME,
                null, null, sink, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Message.Assistant assistant(List<Content> content, StopReason reason) {
        return new Message.Assistant(content, reason, null, Usage.zero(), NOW, MODEL);
    }

    private static String eventName(CodingAgentEvent event) {
        if (event instanceof CodingAgentEvent.RunCompleted) {
            return "RunCompleted";
        }
        return ((CodingAgentEvent.RuntimeEvent) event).event().getClass().getSimpleName();
    }
}
