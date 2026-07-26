package site.pplee.jcode.agentcore.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentMessageTest {
    private static final Instant TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void userCopiesContent() {
        var original = new ArrayList<Content>();
        original.add(new Content.Text("hello"));

        var message = new AgentMessage.User(original, TIMESTAMP);
        original.add(new Content.Text("changed"));

        assertEquals(List.of(new Content.Text("hello")), message.content());
        assertThrows(UnsupportedOperationException.class,
                () -> message.content().add(new Content.Text("changed")));
    }

    @Test
    void assistantCopiesContent() {
        var original = new ArrayList<Content>();
        original.add(new Content.Text("answer"));

        var message = new AgentMessage.Assistant(original, StopReason.STOP, null, TIMESTAMP);
        original.clear();

        assertEquals(List.of(new Content.Text("answer")), message.content());
    }

    @Test
    void toolResultCopiesContent() {
        var original = new ArrayList<Content>();
        original.add(new Content.Text("result"));

        var message = new AgentMessage.ToolResult(
                "call-1", "echo", original, false, false, TIMESTAMP);
        original.clear();

        assertEquals(List.of(new Content.Text("result")), message.content());
    }

    @Test
    void requiredFieldsRejectNull() {
        assertThrows(NullPointerException.class, () -> new AgentMessage.User(null, TIMESTAMP));
        assertThrows(NullPointerException.class, () -> new AgentMessage.User(List.of(), null));
        assertThrows(NullPointerException.class,
                () -> new AgentMessage.Assistant(null, StopReason.STOP, null, TIMESTAMP));
        assertThrows(NullPointerException.class,
                () -> new AgentMessage.Assistant(List.of(), null, null, TIMESTAMP));
        assertThrows(NullPointerException.class,
                () -> new AgentMessage.Assistant(List.of(), StopReason.STOP, null, null));
        assertThrows(NullPointerException.class,
                () -> new AgentMessage.ToolResult(null, "echo", List.of(), false, false, TIMESTAMP));
        assertThrows(NullPointerException.class,
                () -> new AgentMessage.ToolResult("call-1", null, List.of(), false, false, TIMESTAMP));
        assertThrows(NullPointerException.class,
                () -> new AgentMessage.ToolResult("call-1", "echo", null, false, false, TIMESTAMP));
        assertThrows(NullPointerException.class,
                () -> new AgentMessage.ToolResult("call-1", "echo", List.of(), false, false, null));
    }

    @Test
    void successfulAssistantRejectsErrorMessage() {
        for (var reason : List.of(StopReason.STOP, StopReason.TOOL_CALL, StopReason.LENGTH)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AgentMessage.Assistant(List.of(), reason, "unexpected error", TIMESTAMP));
        }
    }

    @Test
    void failedAssistantAcceptsErrorMessage() {
        var error = new AgentMessage.Assistant(
                List.of(), StopReason.ERROR, "provider failed", TIMESTAMP);
        var aborted = new AgentMessage.Assistant(
                List.of(), StopReason.ABORTED, "cancelled", TIMESTAMP);

        assertEquals("provider failed", error.errorMessage());
        assertEquals("cancelled", aborted.errorMessage());
    }
}
