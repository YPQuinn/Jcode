package site.pplee.jcode.agentcore.event;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.AgentContext;
import site.pplee.jcode.agentcore.LoopResult;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;

import com.fasterxml.jackson.databind.node.NullNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEventTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");

    private static Message.User user(String text) {
        return new Message.User(List.of(new Content.Text(text)), T1);
    }

    private static Message.Assistant assistant() {
        return Message.Assistant.of(List.of(new Content.Text("hi")), StopReason.STOP, T1);
    }

    private static Message.ToolResultMessage toolResult(String id) {
        return new Message.ToolResultMessage(id, "echo", List.of(new Content.Text("r")), false, T1);
    }

    private static Content.ToolCall toolCall(String id) {
        return new Content.ToolCall(id, "echo", NullNode.getInstance());
    }

    @Test
    void messageCompletedRejectsNull() {
        assertThrows(NullPointerException.class,
                () -> new AgentEvent.MessageCompleted(null));
    }

    @Test
    void toolStartedRejectsNull() {
        assertThrows(NullPointerException.class, () -> new AgentEvent.ToolStarted(null));
    }

    @Test
    void toolCompletedRejectsNull() {
        assertThrows(NullPointerException.class, () -> new AgentEvent.ToolCompleted(null));
    }

    @Test
    void turnCompletedRejectsNullAssistant() {
        assertThrows(NullPointerException.class, () -> new AgentEvent.TurnCompleted(null, List.of()));
    }

    @Test
    void turnCompletedRejectsNullToolResults() {
        assertThrows(NullPointerException.class, () -> new AgentEvent.TurnCompleted(assistant(), null));
    }

    @Test
    void turnCompletedCopiesAndFreezesToolResults() {
        var original = new ArrayList<Message.ToolResultMessage>();
        original.add(toolResult("c1"));

        var event = new AgentEvent.TurnCompleted(assistant(), original);
        original.add(toolResult("c2"));

        assertEquals(1, event.toolResults().size());
        assertThrows(UnsupportedOperationException.class,
                () -> event.toolResults().add(toolResult("c3")));
    }

    @Test
    void agentCompletedRejectsNull() {
        assertThrows(NullPointerException.class, () -> new AgentEvent.AgentCompleted(null));
    }

    @Test
    void sealedHierarchyCoversAllEventTypes() {
        // Exhaustive switch without default: proves the sealed interface permits exactly these 10.
        java.util.function.Function<AgentEvent, String> label = event -> switch (event) {
            case AgentEvent.AgentStarted ignored -> "agent_started";
            case AgentEvent.TurnStarted ignored -> "turn_started";
            case AgentEvent.MessageStarted m -> "message_started";
            case AgentEvent.MessageUpdated m -> "message_updated";
            case AgentEvent.MessageCompleted m -> "message_completed";
            case AgentEvent.ToolStarted t -> "tool_started";
            case AgentEvent.ToolUpdate t -> "tool_update";
            case AgentEvent.ToolCompleted t -> "tool_completed";
            case AgentEvent.TurnCompleted t -> "turn_completed";
            case AgentEvent.AgentCompleted a -> "agent_completed";
        };

        var context = new AgentContext("sys", List.of(), List.of());
        assertEquals("agent_started", label.apply(new AgentEvent.AgentStarted()));
        assertEquals("turn_started", label.apply(new AgentEvent.TurnStarted()));
        assertEquals("message_started", label.apply(
                new AgentEvent.MessageStarted(StandardAgentMessage.of(user("a")))));
        assertEquals("message_completed", label.apply(
                new AgentEvent.MessageCompleted(StandardAgentMessage.of(user("a")))));
        assertEquals("tool_started", label.apply(new AgentEvent.ToolStarted(toolCall("c1"))));
        assertEquals("tool_update", label.apply(new AgentEvent.ToolUpdate(toolCall("c1"), new Content.Text("partial"))));
        assertEquals("tool_completed", label.apply(new AgentEvent.ToolCompleted(toolResult("c1"))));
        assertEquals("turn_completed", label.apply(new AgentEvent.TurnCompleted(assistant(), List.of())));
        assertEquals("agent_completed", label.apply(new AgentEvent.AgentCompleted(
                new LoopResult(context, List.of()))));
    }

    @Test
    void noopSinkCompletesImmediatelyWithoutBlocking() {
        var sink = AgentEventSink.noop();
        var future = sink.emit(new AgentEvent.AgentStarted()).toCompletableFuture();
        assertTrue(future.isDone());
        assertNull(future.join());
    }

    @Test
    void recordingSinkPreservesAwaitOrder() {
        var recorded = new ArrayList<AgentEvent>();
        AgentEventSink sink = event -> {
            recorded.add(event);
            return CompletableFuture.completedStage(null);
        };

        sink.emit(new AgentEvent.AgentStarted()).toCompletableFuture().join();
        sink.emit(new AgentEvent.TurnStarted()).toCompletableFuture().join();
        sink.emit(new AgentEvent.AgentCompleted(
                new LoopResult(new AgentContext("sys", List.of(), List.of()), List.of())
        )).toCompletableFuture().join();

        assertEquals(3, recorded.size());
        assertTrue(recorded.get(0) instanceof AgentEvent.AgentStarted);
        assertTrue(recorded.get(1) instanceof AgentEvent.TurnStarted);
        assertTrue(recorded.get(2) instanceof AgentEvent.AgentCompleted);
    }
}
