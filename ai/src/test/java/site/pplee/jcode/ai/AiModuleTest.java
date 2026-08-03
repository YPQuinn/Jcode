package site.pplee.jcode.ai;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.tool.ToolSpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code ai} module compiles in isolation and its standard types
 * behave as documented. Also asserts that {@code ai} has no dependency on
 * {@code agent-core}: nothing here imports {@code site.pplee.jcode.agentcore}.
 */
class AiModuleTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef REF = new ModelRef("openai", "openai-responses", "gpt-4o");

    @Test
    void modelRefCarriesProviderApiAndModelId() {
        assertEquals("openai", REF.provider());
        assertEquals("openai-responses", REF.api());
        assertEquals("gpt-4o", REF.modelId());
        assertThrows(NullPointerException.class, () -> new ModelRef(null, "api", "m"));
        assertThrows(IllegalArgumentException.class, () -> new ModelRef(" ", "api", "m"));
    }

    @Test
    void modelResolvesFromRef() {
        var model = REF.toModel();
        assertEquals(REF, model.toRef());
        assertEquals("gpt-4o", model.name());
    }

    @Test
    void sealedMessageHierarchyCoversThreeRoles() {
        var u = new Message.User(List.of(new Content.Text("hi")), T1);
        var a = Message.Assistant.of(List.of(new Content.Text("hi")), StopReason.STOP, T1);
        var t = new Message.ToolResultMessage("c1", "echo", List.of(new Content.Text("ok")), false, T1);

        var label = switch ((Message) u) {
            case Message.User ignored -> "user";
            case Message.Assistant ignored when a.stopReason() == StopReason.STOP -> "assistant";
            case Message.Assistant ignored -> "assistant-other";
            case Message.ToolResultMessage ignored -> "tool";
        };
        assertEquals("user", label);
        assertEquals("assistant", switch ((Message) a) {
            case Message.User ignored -> "user";
            case Message.Assistant ignored -> "assistant";
            case Message.ToolResultMessage ignored -> "tool";
        });
        assertEquals("tool", switch ((Message) t) {
            case Message.User ignored -> "user";
            case Message.Assistant ignored -> "assistant";
            case Message.ToolResultMessage ignored -> "tool";
        });
    }

    @Test
    void assistantRejectsErrorMessageOnNonTerminalStopReason() {
        for (var reason : List.of(StopReason.STOP, StopReason.TOOL_CALL, StopReason.LENGTH)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Message.Assistant(List.of(), reason, "unexpected", Usage.zero(), T1));
        }
    }

    @Test
    void assistantAcceptsErrorMessageOnTerminalFailure() {
        var err = new Message.Assistant(List.of(), StopReason.ERROR, "boom", Usage.zero(), T1);
        var ab = new Message.Assistant(List.of(), StopReason.ABORTED, "cancelled", Usage.zero(), T1);
        assertEquals("boom", err.errorMessage());
        assertEquals("cancelled", ab.errorMessage());
        assertTrue(StopReason.ERROR.isTerminalFailure());
        assertTrue(StopReason.ABORTED.isTerminalFailure());
        assertFalse(StopReason.STOP.isTerminalFailure());
    }

    @Test
    void toolCallAndToolSpecRejectBlankNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new Content.ToolCall("id", " ", NullNode.getInstance()));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolSpec(" ", "desc", NullNode.getInstance()));
    }

    @Test
    void modelRequestCopiesAndFreezesMessagesAndTools() {
        var mapper = new ObjectMapper();
        var messages = new java.util.ArrayList<>(List.<Message>of(new Message.User(List.of(new Content.Text("a")), T1)));
        var tools = new java.util.ArrayList<>(List.<ToolSpec>of(ToolSpec.minimal("echo")));
        var req = new ModelRequest(REF, "sys", messages, tools);
        messages.add(new Message.User(List.of(new Content.Text("b")), T1));
        tools.add(ToolSpec.minimal("failing"));

        assertEquals(1, req.messages().size());
        assertEquals(1, req.tools().size());
        assertThrows(UnsupportedOperationException.class, () -> req.messages().add(messages.get(0)));
        assertThrows(UnsupportedOperationException.class, () -> req.tools().add(tools.get(0)));
    }

    @Test
    void modelRequestCarriesAbsoluteThinkingLevel() {
        var defaultRequest = new ModelRequest(REF, "sys", List.of(), List.of());
        var highRequest = new ModelRequest(
                REF, "sys", List.of(), List.of(), ThinkingLevel.HIGH);

        assertEquals(ThinkingLevel.PROVIDER_DEFAULT, defaultRequest.thinkingLevel());
        assertEquals(ThinkingLevel.HIGH, highRequest.thinkingLevel());
        assertThrows(NullPointerException.class,
                () -> new ModelRequest(REF, "sys", List.of(), List.of(), null));
    }

    @Test
    void usageZeroAndInvariants() {
        var z = Usage.zero();
        assertEquals(0, z.totalTokens());
        assertThrows(IllegalArgumentException.class, () -> new Usage(-1, 0, 0, 0, 0));
    }

    @Test
    void modelClientIsImplementableWithoutAgentCore() {
        ModelClient client = (request, cancellation) -> {
            var stream = new site.pplee.jcode.ai.stream.AssistantMessageStream();
            var msg = Message.Assistant.of(List.of(new Content.Text("ok")), StopReason.STOP, T1);
            stream.push(new site.pplee.jcode.ai.stream.AssistantMessageEvent.Start(msg));
            stream.push(new site.pplee.jcode.ai.stream.AssistantMessageEvent.Done(StopReason.STOP, msg));
            return stream;
        };
        var req = new ModelRequest(REF, "sys", List.of(), List.of());
        var stream = client.stream(req, new CancellationSignal() {
            @Override public boolean isCancelled() { return false; }
            @Override public void throwIfCancelled() { }
        });
        var result = stream.result();
        assertEquals(StopReason.STOP, result.stopReason());
        assertDoesNotThrow(() -> {
            @SuppressWarnings("unused")
            var ignored = new CancellationSignal() {
                @Override public boolean isCancelled() { return false; }
                @Override public void throwIfCancelled() { }
            };
        });
    }
}
