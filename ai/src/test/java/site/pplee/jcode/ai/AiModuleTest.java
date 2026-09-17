package site.pplee.jcode.ai;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.client.CacheRetention;
import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.client.PromptCacheOptions;
import site.pplee.jcode.ai.client.ToolChoice;
import site.pplee.jcode.ai.concurrent.CancellationRegistration;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.ModelReplayState;
import site.pplee.jcode.ai.message.ResponseMetadata;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.tool.GrammarSyntax;
import site.pplee.jcode.ai.tool.Requirement;
import site.pplee.jcode.ai.tool.ToolInputConstraint;
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
    void toolSpecThreeArgumentConstructorDefaultsToNoneConstraint() {
        var spec = new ToolSpec("echo", "desc", NullNode.getInstance());
        assertEquals(ToolInputConstraint.none(), spec.constraint());
        assertEquals(new ToolInputConstraint.None(), ToolSpec.minimal("echo").constraint());
    }

    @Test
    void toolInputConstraintRejectsBlankGrammarVariantsAndNullRequirement() {
        assertThrows(NullPointerException.class, () -> new ToolInputConstraint.JsonSchema(null));
        assertThrows(NullPointerException.class,
                () -> new ToolInputConstraint.Grammar(null, Requirement.PREFER));
        assertThrows(IllegalArgumentException.class, () -> new ToolInputConstraint.Grammar(
                java.util.Map.of(GrammarSyntax.LARK, "   "), Requirement.REQUIRE));
        var grammar = new ToolInputConstraint.Grammar(
                new java.util.LinkedHashMap<>(java.util.Map.of(GrammarSyntax.REGEX, "a+")),
                Requirement.PREFER);
        assertEquals("a+", grammar.variants().get(GrammarSyntax.REGEX));
        assertThrows(UnsupportedOperationException.class,
                () -> grammar.variants().put(GrammarSyntax.LARK, "start: /x/"));
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
        assertEquals(ModelRequestOptions.defaults(), defaultRequest.options());
        assertEquals(ModelRequestOptions.defaults(), highRequest.options());
        assertThrows(NullPointerException.class,
                () -> new ModelRequest(REF, "sys", List.of(), List.of(), null));
    }

    @Test
    void modelRequestOptionsValidateAndDefault() {
        var options = ModelRequestOptions.defaults()
                .withMaxOutputTokens(32)
                .withTemperature(0.0d)
                .withToolChoice(ToolChoice.required())
                .withPromptCache(PromptCacheOptions.defaults()
                        .withRetention(CacheRetention.SHORT)
                        .withCacheKey("cache-a")
                        .withSessionAffinityId("session-a"));
        var request = new ModelRequest(REF, "sys", List.of(), List.of(), ThinkingLevel.LOW, options);

        assertEquals(32, request.options().maxOutputTokens());
        assertEquals(0.0d, request.options().temperature());
        assertEquals(ToolChoice.required(), request.options().toolChoice());
        assertEquals(CacheRetention.SHORT, request.options().promptCache().retention());
        assertEquals("cache-a", request.options().promptCache().cacheKey());
        assertEquals("session-a", request.options().promptCache().sessionAffinityId());
        assertEquals(ToolChoice.Mode.AUTO, ModelRequestOptions.defaults().toolChoice());
        assertEquals(CacheRetention.PROVIDER_DEFAULT, PromptCacheOptions.defaults().retention());
        assertEquals(CacheRetention.NONE, PromptCacheOptions.none().retention());

        assertThrows(IllegalArgumentException.class,
                () -> ModelRequestOptions.defaults().withMaxOutputTokens(0));
        assertThrows(IllegalArgumentException.class,
                () -> ModelRequestOptions.defaults().withMaxOutputTokens(-1));
        assertThrows(IllegalArgumentException.class,
                () -> ModelRequestOptions.defaults().withTemperature(-0.1d));
        assertThrows(IllegalArgumentException.class,
                () -> ModelRequestOptions.defaults().withTemperature(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> ModelRequestOptions.defaults().withTemperature(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> ToolChoice.specific(" "));
        assertEquals(null, new PromptCacheOptions(CacheRetention.SHORT, "  ", "  ").cacheKey());
        assertEquals(null, new PromptCacheOptions(CacheRetention.SHORT, "  ", "  ").sessionAffinityId());
    }

    @Test
    void promptCacheOptionsToStringHidesValuesAndCascades() {
        var cache = new PromptCacheOptions(CacheRetention.SHORT, "cache-secret-value", "session-secret-value");
        assertEquals("cache-secret-value", cache.cacheKey());
        assertEquals("session-secret-value", cache.sessionAffinityId());
        assertTrue(cache.toString().contains("SHORT"));
        assertTrue(cache.toString().contains("cacheKey=present"));
        assertTrue(cache.toString().contains("sessionAffinityId=present"));
        assertFalse(cache.toString().contains("cache-secret-value"));
        assertFalse(cache.toString().contains("session-secret-value"));
        assertEquals("PromptCacheOptions[retention=PROVIDER_DEFAULT, cacheKey=absent, sessionAffinityId=absent]",
                PromptCacheOptions.defaults().toString());

        var options = ModelRequestOptions.defaults().withPromptCache(cache);
        var request = new ModelRequest(REF, "sys", List.of(), List.of(), ThinkingLevel.LOW, options);
        assertFalse(options.toString().contains("cache-secret-value"));
        assertFalse(options.toString().contains("session-secret-value"));
        assertFalse(request.toString().contains("cache-secret-value"));
        assertFalse(request.toString().contains("session-secret-value"));
    }

    @Test
    void modelReplayStateRejectsBlankAndRedactsPayload() {
        assertThrows(IllegalArgumentException.class, () -> new ModelReplayState(" ", "payload"));
        assertThrows(IllegalArgumentException.class, () -> new ModelReplayState("fmt", " "));
        assertThrows(NullPointerException.class, () -> new ModelReplayState(null, "payload"));
        var state = new ModelReplayState("test-format/v1", "secret-payload");
        assertEquals("test-format/v1", state.format());
        assertEquals("secret-payload", state.payload());
        assertFalse(state.toString().contains("secret-payload"));
        assertTrue(state.toString().contains("test-format/v1"));
    }

    @Test
    void imageRejectsInvalidMediaTypeAndDataUrlAndRedactsPayload() {
        var image = new Content.Image("IMAGE/PNG", "AA==");
        assertEquals("image/png", image.mediaType());
        assertEquals("AA==", image.base64Data());
        assertFalse(image.toString().contains("AA=="));
        assertTrue(image.toString().contains("image/png"));
        assertTrue(image.toString().contains("encodedLength=4"));

        assertThrows(IllegalArgumentException.class, () -> new Content.Image(" ", "AA=="));
        assertThrows(IllegalArgumentException.class, () -> new Content.Image("text/plain", "AA=="));
        assertThrows(IllegalArgumentException.class, () -> new Content.Image("image/", "AA=="));
        assertThrows(IllegalArgumentException.class, () -> new Content.Image("image/png", " "));
        assertThrows(IllegalArgumentException.class,
                () -> new Content.Image("image/png", "data:image/png;base64,AA=="));
        assertThrows(IllegalArgumentException.class, () -> new Content.Image("image/png", "@@@"));
        assertThrows(IllegalArgumentException.class, () -> new Content.Image("image/png", "AA-="));
        var label = switch ((Content) image) {
            case Content.Text ignored -> "text";
            case Content.Thinking ignored -> "thinking";
            case Content.ToolCall ignored -> "tool";
            case Content.Image ignored -> "image";
        };
        assertEquals("image", label);
    }

    @Test
    void textThinkingAndAssistantCompatibilityConstructorsLeaveReplayUnset() {
        var text = new Content.Text("hello");
        var thinking = new Content.Thinking("reason");
        var assistant = new Message.Assistant(List.of(text), StopReason.STOP, null, Usage.zero(), T1);
        var of = Message.Assistant.of(List.of(thinking), StopReason.STOP, T1);

        assertEquals("hello", text.text());
        assertEquals(null, text.replayState());
        assertEquals("reason", thinking.text());
        assertEquals(null, thinking.replayState());
        assertEquals(null, assistant.sourceModel());
        assertEquals(null, of.sourceModel());
        assertEquals(ResponseMetadata.empty(), assistant.metadata());
        assertEquals(ResponseMetadata.empty(), of.metadata());
        assertEquals(text, new Content.Text("hello", null));
        assertEquals(thinking, new Content.Thinking("reason", null));
    }

    @Test
    void usageZeroAndInvariants() {
        var z = Usage.zero();
        assertEquals(0, z.totalTokens());
        assertEquals(0, z.reasoningTokens());
        assertTrue(z.cost().isEmpty());
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
            @Override public CancellationRegistration onCancellation(Runnable listener) {
                return () -> { };
            }
        });
        var result = stream.result();
        assertEquals(StopReason.STOP, result.stopReason());
        assertDoesNotThrow(() -> {
            @SuppressWarnings("unused")
            var ignored = new CancellationSignal() {
                @Override public boolean isCancelled() { return false; }
                @Override public void throwIfCancelled() { }
                @Override public CancellationRegistration onCancellation(Runnable listener) {
                    return () -> { };
                }
            };
        });
    }
}
