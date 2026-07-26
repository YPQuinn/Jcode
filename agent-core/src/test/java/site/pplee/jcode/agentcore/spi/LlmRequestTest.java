package site.pplee.jcode.agentcore.spi;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.concurrent.CancellationToken;
import site.pplee.jcode.agentcore.model.AgentMessage;
import site.pplee.jcode.agentcore.model.Content;
import site.pplee.jcode.agentcore.model.ModelRef;
import site.pplee.jcode.agentcore.model.ToolResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmRequestTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef MODEL = new ModelRef("openai", "gpt-4o");

    private static AgentMessage.User user(String text) {
        return new AgentMessage.User(List.of(new Content.Text(text)), T1);
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
        messages.add(user("a"));
        var tools = new ArrayList<AgentTool<?>>();
        tools.add(dummyTool());

        var request = new LlmRequest(MODEL, "sys", messages, tools);
        messages.add(user("b"));
        tools.add(dummyTool());

        assertEquals(List.of(user("a")), request.messages());
        assertEquals(1, request.tools().size());
        assertThrows(UnsupportedOperationException.class,
                () -> request.messages().add(user("b")));
        assertThrows(UnsupportedOperationException.class,
                () -> request.tools().add(dummyTool()));
    }

    @Test
    void rejectsNullFields() {
        assertThrows(NullPointerException.class,
                () -> new LlmRequest(null, "sys", List.of(), List.of()));
        assertThrows(NullPointerException.class,
                () -> new LlmRequest(MODEL, null, List.of(), List.of()));
        assertThrows(NullPointerException.class,
                () -> new LlmRequest(MODEL, "sys", null, List.of()));
        assertThrows(NullPointerException.class,
                () -> new LlmRequest(MODEL, "sys", List.of(), null));
    }

    @Test
    void allowsEmptySystemPrompt() {
        var request = new LlmRequest(MODEL, "", List.of(), List.of());
        assertEquals("", request.systemPrompt());
    }
}
