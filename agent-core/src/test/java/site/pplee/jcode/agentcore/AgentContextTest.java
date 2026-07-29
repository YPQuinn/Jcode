package site.pplee.jcode.agentcore;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;

import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;

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

    private static Message.User user(String text, Instant t) {
        return new Message.User(List.of(new Content.Text(text)), t);
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
            public CompletionStage<ToolExecutionResult> execute(
                    String toolCallId,
                    Object arguments,
                    ToolUpdateSink updates,
                    CancellationSignal cancellation
            ) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void copiesMessagesAndToolsOnConstruction() {
        var messages = new ArrayList<>(List.<site.pplee.jcode.agentcore.message.AgentMessage>of(
                StandardAgentMessage.of(user("a", T1))));
        var tools = new ArrayList<>(List.<AgentTool<?>>of(dummyTool()));

        var context = new AgentContext("sys", messages, tools);
        messages.add(StandardAgentMessage.of(user("b", T2)));
        tools.add(dummyTool());

        assertEquals(List.of(StandardAgentMessage.of(user("a", T1))), context.messages());
        assertEquals(1, context.tools().size());
        assertThrows(UnsupportedOperationException.class,
                () -> context.messages().add(StandardAgentMessage.of(user("b", T2))));
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
        var context = new AgentContext("sys", List.of(StandardAgentMessage.of(user("a", T1))), List.of());

        var next = context.append(StandardAgentMessage.of(user("b", T2)));

        assertNotSame(context, next);
        assertEquals(List.of(StandardAgentMessage.of(user("a", T1))), context.messages());
        assertEquals(List.of(StandardAgentMessage.of(user("a", T1)), StandardAgentMessage.of(user("b", T2))),
                next.messages());
    }

    @Test
    void appendRejectsNull() {
        var context = new AgentContext("sys", List.of(), List.of());
        assertThrows(NullPointerException.class, () -> context.append(null));
    }

    @Test
    void appendAllReturnsNewContextWithAllMessages() {
        var context = new AgentContext("sys", List.of(StandardAgentMessage.of(user("a", T1))), List.of());

        var next = context.appendAll(List.of(
                StandardAgentMessage.of(user("b", T2)),
                StandardAgentMessage.of(user("c", T1))));

        assertNotSame(context, next);
        assertEquals(List.of(StandardAgentMessage.of(user("a", T1))), context.messages());
        assertEquals(List.of(
                StandardAgentMessage.of(user("a", T1)),
                StandardAgentMessage.of(user("b", T2)),
                StandardAgentMessage.of(user("c", T1))), next.messages());
    }

    @Test
    void appendAllEmptyReturnsSameInstance() {
        var context = new AgentContext("sys", List.of(StandardAgentMessage.of(user("a", T1))), List.of());

        var next = context.appendAll(List.of());

        assertSame(context, next);
    }

    @Test
    void appendAllRejectsNull() {
        var context = new AgentContext("sys", List.of(), List.of());
        assertThrows(NullPointerException.class, () -> context.appendAll(null));
    }

    /** The tool's default spec composes a minimal ToolSpec from name + defaults. */
    @Test
    void dummyToolExposesMinimalSpec() {
        var tool = dummyTool();
        var spec = tool.spec();
        assertEquals("dummy", spec.name());
        assertEquals("", spec.description());
        assertEquals(ToolExecutionMode.PARALLEL, tool.executionMode());
    }
}
