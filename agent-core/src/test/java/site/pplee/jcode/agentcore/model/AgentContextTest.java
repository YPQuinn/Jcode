package site.pplee.jcode.agentcore.model;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.concurrent.CancellationToken;
import site.pplee.jcode.agentcore.spi.AgentTool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentContextTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-02T00:00:00Z");

    private static AgentMessage.User user(String text, Instant t) {
        return new AgentMessage.User(List.of(new Content.Text(text)), t);
    }

    private static AgentTool<?> dummyTool() {
        return new AgentTool<Object>() {
            @Override
            public String name() {
                return "dummy";
            }

            @Override
            public Class<Object> argumentType() {
                return Object.class;
            }

            @Override
            public CompletionStage<ToolResult> execute(
                    String toolCallId,
                    Object arguments,
                    CancellationToken cancellation
            ) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void copiesMessagesAndToolsOnConstruction() {
        var messages = new ArrayList<AgentMessage>();
        messages.add(user("a", T1));
        var tools = new ArrayList<AgentTool<?>>();
        tools.add(dummyTool());

        var context = new AgentContext("sys", messages, tools);
        messages.add(user("b", T2));
        tools.add(dummyTool());

        assertEquals(List.of(user("a", T1)), context.messages());
        assertEquals(1, context.tools().size());
        assertThrows(UnsupportedOperationException.class,
                () -> context.messages().add(user("b", T2)));
        assertThrows(UnsupportedOperationException.class,
                () -> context.tools().add(dummyTool()));
    }

    @Test
    void rejectsNullFields() {
        assertThrows(NullPointerException.class,
                () -> new AgentContext(null, List.of(), List.of()));
        assertThrows(NullPointerException.class,
                () -> new AgentContext("sys", null, List.of()));
        assertThrows(NullPointerException.class,
                () -> new AgentContext("sys", List.of(), null));
    }

    @Test
    void appendReturnsNewContextWithMessage() {
        var context = new AgentContext("sys", List.of(user("a", T1)), List.of());

        var next = context.append(user("b", T2));

        assertNotSame(context, next);
        assertEquals(List.of(user("a", T1)), context.messages());
        assertEquals(List.of(user("a", T1), user("b", T2)), next.messages());
    }

    @Test
    void appendRejectsNull() {
        var context = new AgentContext("sys", List.of(), List.of());
        assertThrows(NullPointerException.class, () -> context.append(null));
    }

    @Test
    void appendAllReturnsNewContextWithAllMessages() {
        var context = new AgentContext("sys", List.of(user("a", T1)), List.of());

        var next = context.appendAll(List.of(user("b", T2), user("c", T1)));

        assertNotSame(context, next);
        assertEquals(List.of(user("a", T1)), context.messages());
        assertEquals(List.of(user("a", T1), user("b", T2), user("c", T1)), next.messages());
    }

    @Test
    void appendAllEmptyReturnsSameInstance() {
        var context = new AgentContext("sys", List.of(user("a", T1)), List.of());

        var next = context.appendAll(List.of());

        assertSame(context, next);
    }

    @Test
    void appendAllRejectsNull() {
        var context = new AgentContext("sys", List.of(), List.of());
        assertThrows(NullPointerException.class, () -> context.appendAll(null));
    }
}
