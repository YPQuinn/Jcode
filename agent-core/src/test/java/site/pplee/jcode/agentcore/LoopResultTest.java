package site.pplee.jcode.agentcore;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoopResultTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");

    private static Message.User user(String text) {
        return new Message.User(List.of(new Content.Text(text)), T1);
    }

    @Test
    void copiesNewMessagesOnConstruction() {
        var original = new ArrayList<>(List.<site.pplee.jcode.agentcore.message.AgentMessage>of(
                StandardAgentMessage.of(user("a"))));

        var context = new AgentContext("sys", List.of(), List.of());
        var result = new LoopResult(context, original);
        original.clear();

        assertEquals(1, result.newMessages().size());
        assertEquals(StandardAgentMessage.of(user("a")), result.newMessages().get(0));
        assertThrows(UnsupportedOperationException.class,
                () -> result.newMessages().add(StandardAgentMessage.of(user("b"))));
    }

    @Test
    void rejectsNullFields() {
        var context = new AgentContext("sys", List.of(), List.of());
        assertThrows(NullPointerException.class, () -> new LoopResult(null, List.of()));
        assertThrows(NullPointerException.class, () -> new LoopResult(context, null));
    }

    @Test
    void contextAccessorReturnsOriginal() {
        var context = new AgentContext("sys", List.of(StandardAgentMessage.of(user("a"))), List.of());
        var result = new LoopResult(context, List.of(StandardAgentMessage.of(user("b"))));

        assertEquals(context, result.context());
    }
}
