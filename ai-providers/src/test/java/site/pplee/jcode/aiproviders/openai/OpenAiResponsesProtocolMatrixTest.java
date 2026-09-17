package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.client.CacheRetention;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.client.PromptCacheOptions;
import site.pplee.jcode.ai.client.ToolChoice;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.tool.GrammarSyntax;
import site.pplee.jcode.ai.tool.Requirement;
import site.pplee.jcode.ai.tool.ToolInputConstraint;
import site.pplee.jcode.ai.tool.ToolSpec;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Incremental OAI-018 matrix for second-batch rules delivered in PR 1–6.
 * Each protocol rule has a positive and a negative case; combinations
 * are not exploded into a cartesian product.
 */
class OpenAiResponsesProtocolMatrixTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef REF = new ModelRef("openai", "openai-responses", "gpt-4o-mini");
    private final OpenAiRequestMapper mapper = new OpenAiRequestMapper(MAPPER);

    @Nested
    class ContentAndPromptRole {
        @Test
        void emptyToolResultAndVisionFallback() {
            var callId = OpenAiToolCallIds.encode("call_abc", "fc_1");
            var image = new Content.Image("image/png", "AA==");
            var request = new ModelRequest(REF, "sys", List.of(
                    new Message.User(List.of(image), T1),
                    new Message.Assistant(List.of(
                            new Content.ToolCall(callId, "look", MAPPER.createObjectNode())
                    ), StopReason.TOOL_CALL, null, Usage.zero(), T1),
                    new Message.ToolResultMessage(callId, "look", List.of(), false, T1)
            ), List.of());

            var vision = mapper.map(request, new OpenAiModelCapabilities(false, Map.of(), true, false));
            assertEquals("input_image", vision.get("input").get(1).get("content").get(0).get("type").asText());
            assertEquals(OpenAiTranscriptPlanner.NO_TOOL_OUTPUT,
                    vision.get("input").get(3).get("output").asText());

            var hidden = mapper.map(request, OpenAiModelCapabilities.noReasoning());
            assertTrue(hidden.get("input").get(1).get("content").get(0).get("text").asText()
                    .contains("Image omitted"));
        }

        @Test
        void developerSystemAndForcedSystem() {
            var preferred = new OpenAiModelCapabilities(true, Map.of(), false, true);
            var request = new ModelRequest(REF, "sys", List.of(), List.of());
            assertEquals("developer", mapper.map(request, preferred).get("input").get(0).get("role").asText());
            assertEquals("system", mapper.map(request, preferred, OpenAiResponsesCompatibility.forceSystem())
                    .get("input").get(0).get("role").asText());
        }
    }

    @Nested
    class RequestControls {
        @Test
        void tokenClampTemperatureToolChoiceAndServiceTier() {
            var sampling = new OpenAiModelCapabilities(false, Map.of(), false, false, true, true);
            var echo = ToolSpec.minimal("echo");

            var unset = mapper.map(new ModelRequest(REF, "", List.of(), List.of()), sampling);
            assertFalse(unset.has("max_output_tokens"));
            assertFalse(unset.has("temperature"));
            assertFalse(unset.has("tool_choice"));
            assertFalse(unset.has("service_tier"));

            var request = new ModelRequest(REF, "", List.of(), List.of(echo), ThinkingLevel.PROVIDER_DEFAULT,
                    ModelRequestOptions.defaults()
                            .withMaxOutputTokens(8)
                            .withTemperature(0.5d)
                            .withToolChoice(ToolChoice.required()));
            var payload = mapper.map(request, sampling, OpenAiResponsesCompatibility.openai(),
                    OpenAiServiceTier.DEFAULT);
            assertEquals(16, payload.get("max_output_tokens").asInt());
            assertEquals(0.5d, payload.get("temperature").asDouble());
            assertEquals("required", payload.get("tool_choice").asText());
            assertEquals("default", payload.get("service_tier").asText());
        }

        @Test
        void unsupportedExplicitControlsFail() {
            var conservative = new OpenAiResponsesCompatibility(true);
            assertThrows(IllegalArgumentException.class, () -> mapper.map(
                    new ModelRequest(REF, "", List.of(), List.of(), ThinkingLevel.PROVIDER_DEFAULT,
                            ModelRequestOptions.defaults().withMaxOutputTokens(32)),
                    null, conservative));
            assertThrows(IllegalArgumentException.class, () -> mapper.map(
                    new ModelRequest(REF, "", List.of(), List.of(), ThinkingLevel.PROVIDER_DEFAULT,
                            ModelRequestOptions.defaults().withTemperature(0.2d)),
                    OpenAiModelCapabilities.noReasoning()));
            assertThrows(IllegalArgumentException.class, () -> mapper.map(
                    new ModelRequest(REF, "", List.of(), List.of(), ThinkingLevel.PROVIDER_DEFAULT,
                            ModelRequestOptions.defaults().withToolChoice(ToolChoice.none())),
                    OpenAiModelCapabilities.noReasoning()));
        }
    }

    @Nested
    class PromptCacheAndAffinity {
        @Test
        void fourRetentionsAndOpenAiVersusOpenRouter() {
            var shortRequest = requestWithCache(CacheRetention.SHORT, "key", "session-1");
            var shortPayload = mapper.map(shortRequest, null);
            assertEquals("key", shortPayload.get("prompt_cache_key").asText());
            assertFalse(shortPayload.has("prompt_cache_retention"));

            var longRequest = requestWithCache(CacheRetention.LONG, "key", "session-1");
            assertEquals("24h", mapper.map(longRequest, null).get("prompt_cache_retention").asText());

            var none = requestWithCache(CacheRetention.NONE, "key", "session-1");
            assertFalse(mapper.map(none, null).has("prompt_cache_key"));

            var defaults = requestWithCache(CacheRetention.PROVIDER_DEFAULT, "key", "session-1");
            var defaultPayload = mapper.map(defaults, null);
            assertEquals("key", defaultPayload.get("prompt_cache_key").asText());
            assertFalse(defaultPayload.has("prompt_cache_retention"));
            assertFalse(mapper.map(requestWithCache(CacheRetention.PROVIDER_DEFAULT, null, "session-1"), null)
                    .has("prompt_cache_key"));
            assertThrows(IllegalArgumentException.class, () -> mapper.map(
                    defaults, null, new OpenAiResponsesCompatibility(true)));

            assertEquals(OpenAiEndpointProfile.OPENAI,
                    OpenAiResponsesCompatibility.openai().endpointProfile());
            assertEquals(OpenAiEndpointProfile.OPENROUTER,
                    OpenAiResponsesCompatibility.openRouter().endpointProfile());
            assertFalse(OpenAiResponsesCompatibility.openRouter().longCacheRetention());
            assertThrows(IllegalArgumentException.class, () -> mapper.map(
                    longRequest, null, OpenAiResponsesCompatibility.openRouter()));
        }

        @Test
        void cacheKeyUsesUnicodeCodePoints() {
            String key = "x".repeat(63) + "👍y";
            var request = requestWithCache(CacheRetention.SHORT, key, null);
            String sent = mapper.map(request, null).get("prompt_cache_key").asText();
            assertEquals(64, sent.codePointCount(0, sent.length()));
            assertTrue(sent.endsWith("👍"));
        }
    }

    @Nested
    class Tools {
        @Test
        void plainStrictGrammarPreferAndRequire() throws Exception {
            var schema = MAPPER.readTree(
                    "{\"type\":\"object\",\"properties\":{\"payload\":{\"type\":\"string\"}},\"required\":[\"payload\"]}");
            var plain = ToolSpec.minimal("echo");
            var strict = new ToolSpec("read", "read", schema,
                    new ToolInputConstraint.JsonSchema(Requirement.PREFER));
            var grammar = new ToolSpec("sample_tool", "g", schema,
                    new ToolInputConstraint.Grammar(Map.of(GrammarSyntax.LARK, "start: /x/"), Requirement.PREFER));
            var requireGrammar = new ToolSpec("sample_tool", "g", schema,
                    new ToolInputConstraint.Grammar(Map.of(GrammarSyntax.REGEX, "[a-z]+"), Requirement.REQUIRE));

            var official = mapper.map(new ModelRequest(REF, "", List.of(), List.of(plain, strict, grammar)), null);
            assertFalse(official.get("tools").get(0).has("strict"));
            assertTrue(official.get("tools").get(1).get("strict").asBoolean());
            assertEquals("custom", official.get("tools").get(2).get("type").asText());
            assertEquals("lark", official.get("tools").get(2).get("format").get("syntax").asText());

            var conservative = new OpenAiResponsesCompatibility(true);
            var fallback = mapper.map(new ModelRequest(REF, "", List.of(), List.of(strict, grammar)),
                    null, conservative);
            assertFalse(fallback.get("tools").get(0).has("strict"));
            assertEquals("function", fallback.get("tools").get(1).get("type").asText());
            assertThrows(IllegalArgumentException.class, () -> mapper.map(
                    new ModelRequest(REF, "", List.of(), List.of(requireGrammar)), null, conservative));
        }

        @Test
        void customStreamDeltaDoneOnlyAndMalformed() throws Exception {
            var events = new OpenAiEventMapper(REF, Map.of("sample_tool", "payload"));
            var streamed = events.onEvent("response.output_item.added", MAPPER.readTree(
                    "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\",\"input\":\"\"}}"));
            streamed = new java.util.ArrayList<>(streamed);
            streamed.addAll(events.onEvent("response.custom_tool_call_input.delta", MAPPER.readTree(
                    "{\"output_index\":0,\"delta\":\"hi\"}")));
            streamed.addAll(events.onEvent("response.output_item.done", MAPPER.readTree(
                    "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\",\"input\":\"hi\"}}")));
            assertInstanceOf(AssistantMessageEvent.ToolCallStart.class, streamed.get(0));
            assertEquals("hi", ((AssistantMessageEvent.ToolCallEnd) streamed.get(streamed.size() - 1))
                    .toolCall().arguments().get("payload").asText());

            var unknown = new OpenAiEventMapper(REF, Map.of());
            assertThrows(IllegalStateException.class, () -> unknown.onEvent("response.output_item.done",
                    MAPPER.readTree(
                            "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\",\"input\":\"x\"}}")));
        }
    }

    @Nested
    class UsageAndMetadata {
        @Test
        void reasoningCacheTokensAndAbsentCost() throws Exception {
            var events = new OpenAiEventMapper(REF);
            var done = (AssistantMessageEvent.Done) events.onEvent("response.completed", MAPPER.readTree(
                    "{\"response\":{\"id\":\"resp_m\",\"status\":\"completed\",\"usage\":{\"input_tokens\":20,\"output_tokens\":6,\"total_tokens\":26,\"input_tokens_details\":{\"cached_tokens\":4,\"cache_write_tokens\":2},\"output_tokens_details\":{\"reasoning_tokens\":3}}}}"))
                    .get(0);
            assertEquals(14, done.message().usage().input());
            assertEquals(4, done.message().usage().cacheRead());
            assertEquals(2, done.message().usage().cacheWrite());
            assertEquals(3, done.message().usage().reasoningTokens());
            assertEquals(26, done.message().usage().totalTokens());
            assertTrue(done.message().usage().cost().isEmpty());
            assertEquals("resp_m", done.message().metadata().responseId().orElseThrow());
            assertEquals("completed", done.message().metadata().rawTerminalReason().orElseThrow());
        }

        @Test
        void illegalUsageAndPricedTierMultiplier() throws Exception {
            assertThrows(IllegalStateException.class, () -> new OpenAiEventMapper(REF).onEvent(
                    "response.completed",
                    MAPPER.readTree("{\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"output_tokens_details\":{\"reasoning_tokens\":2}}}}")));

            var pricing = OpenAiPricing.of("USD", Map.of(REF.modelId(), new OpenAiPricing.ModelPrice(
                    new OpenAiPricing.TokenRates(
                            java.math.BigDecimal.ONE,
                            java.math.BigDecimal.ONE,
                            java.math.BigDecimal.ZERO,
                            java.math.BigDecimal.ZERO),
                    java.util.List.of(),
                    Map.of(OpenAiServiceTier.PRIORITY, new java.math.BigDecimal("2")))));
            var mapper = new OpenAiEventMapper(REF, Map.of(), java.util.Optional.of(pricing), java.util.Optional.empty());
            var done = (AssistantMessageEvent.Done) mapper.onEvent("response.completed", MAPPER.readTree(
                    "{\"response\":{\"status\":\"completed\",\"service_tier\":\"priority\",\"usage\":{\"input_tokens\":1000000,\"output_tokens\":0,\"total_tokens\":1000000}}}"))
                    .get(0);
            assertEquals(0, done.message().usage().cost().orElseThrow().total().compareTo(new java.math.BigDecimal("2")));
        }

        @Test
        void incompleteErrorKeepsMetadataSseErrorDoesNotInvent() throws Exception {
            var incomplete = (AssistantMessageEvent.Error) new OpenAiEventMapper(REF).onEvent(
                    "response.incomplete",
                    MAPPER.readTree("{\"response\":{\"id\":\"resp_i\",\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"}}}"))
                    .get(0);
            assertEquals("resp_i", incomplete.error().metadata().responseId().orElseThrow());
            assertEquals("incomplete.content_filter", incomplete.error().metadata().rawTerminalReason().orElseThrow());

            var failed = (AssistantMessageEvent.Error) new OpenAiEventMapper(REF).onEvent(
                    "response.failed",
                    MAPPER.readTree("{\"response\":{\"id\":\"resp_f\",\"status\":\"failed\"}}"))
                    .get(0);
            assertEquals("resp_f", failed.error().metadata().responseId().orElseThrow());

            var sse = (AssistantMessageEvent.Error) new OpenAiEventMapper(REF).onEvent(
                    "error", MAPPER.readTree("{\"id\":\"resp_fake\",\"status\":\"failed\",\"message\":\"x\"}"))
                    .get(0);
            assertTrue(sse.error().metadata().isEmpty());
        }

        @Test
        void createdIdFallbackAndMalformedDetailsAndUnknownTier() throws Exception {
            var created = new OpenAiEventMapper(REF);
            assertTrue(created.onEvent("response.created", MAPPER.readTree(
                    "{\"response\":{\"id\":\"resp_created\"}}")).isEmpty());
            var done = (AssistantMessageEvent.Done) created.onEvent("response.completed", MAPPER.readTree(
                    "{\"response\":{\"status\":\"completed\"}}")).get(0);
            assertEquals("resp_created", done.message().metadata().responseId().orElseThrow());

            assertThrows(IllegalStateException.class, () -> new OpenAiEventMapper(REF).onEvent(
                    "response.completed",
                    MAPPER.readTree("{\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"input_tokens_details\":\"nope\"}}}")));

            var pricing = OpenAiPricing.of("USD", Map.of(REF.modelId(), new OpenAiPricing.ModelPrice(
                    new OpenAiPricing.TokenRates(
                            java.math.BigDecimal.ONE,
                            java.math.BigDecimal.ZERO,
                            java.math.BigDecimal.ZERO,
                            java.math.BigDecimal.ZERO),
                    java.util.List.of(),
                    Map.of(OpenAiServiceTier.PRIORITY, new java.math.BigDecimal("2")))));
            var unknown = new OpenAiEventMapper(REF, Map.of(), java.util.Optional.of(pricing),
                    java.util.Optional.of(OpenAiServiceTier.PRIORITY));
            var priced = (AssistantMessageEvent.Done) unknown.onEvent("response.completed", MAPPER.readTree(
                    "{\"response\":{\"service_tier\":\"future_tier\",\"usage\":{\"input_tokens\":1000000,\"output_tokens\":0,\"total_tokens\":1000000}}}"))
                    .get(0);
            assertEquals(0, priced.message().usage().cost().orElseThrow().total().compareTo(java.math.BigDecimal.ONE));
        }
    }

    @Nested
    class Unicode {
        private static final String LONE_HIGH = "\uD83D";
        private static final String LONE_LOW = "\uDC4D";
        private static final String EMOJI = "\uD83D\uDC4D";

        @Test
        void validBmpEmojiAndNestedJsonAreSanitizedOrPreserved() throws Exception {
            var schema = MAPPER.readTree(
                    "{\"type\":\"object\",\"properties\":{\"payload\":{\"type\":\"string\",\"description\":\"ok "
                            + EMOJI + "\"}},\"required\":[\"payload\"]}");
            var arguments = MAPPER.createObjectNode();
            arguments.put("payload", "nested " + LONE_HIGH + EMOJI);
            var callId = OpenAiToolCallIds.encode("call_abc", "fc_1");
            var request = new ModelRequest(REF, "sys " + EMOJI, List.of(
                    new Message.User(List.of(new Content.Text("bmp café")), T1),
                    new Message.Assistant(List.of(
                            new Content.ToolCall(callId, "echo", arguments)
                    ), StopReason.TOOL_CALL, null, Usage.zero(), T1),
                    new Message.ToolResultMessage(callId, "echo",
                            List.of(new Content.Text("out " + LONE_LOW)), false, T1)
            ), List.of(new ToolSpec("echo", "d", schema)));

            var payload = mapper.map(request, null);
            assertEquals("sys " + EMOJI, payload.get("input").get(0).get("content").get(0).get("text").asText());
            assertEquals("bmp café", payload.get("input").get(1).get("content").get(0).get("text").asText());
            assertEquals("nested " + EMOJI,
                    MAPPER.readTree(payload.get("input").get(2).get("arguments").asText()).get("payload").asText());
            assertEquals("out ", payload.get("input").get(3).get("output").asText());
            assertEquals("ok " + EMOJI, payload.get("tools").get(0).get("parameters")
                    .get("properties").get("payload").get("description").asText());
            assertEquals("nested " + LONE_HIGH + EMOJI, arguments.get("payload").asText());
        }

        @Test
        void unpairedIdentityFieldsFail() {
            assertThrows(IllegalArgumentException.class, () -> mapper.map(
                    new ModelRequest(new ModelRef("openai", "openai-responses", "gpt" + LONE_HIGH),
                            "", List.of(), List.of()), null));
            assertThrows(IllegalArgumentException.class, () -> mapper.map(
                    new ModelRequest(REF, "", List.of(), List.of(ToolSpec.minimal("echo" + LONE_LOW))), null));
            var schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties").putObject("x" + LONE_HIGH).put("type", "string");
            assertThrows(IllegalArgumentException.class, () -> mapper.map(
                    new ModelRequest(REF, "", List.of(), List.of(new ToolSpec("echo", "", schema))), null));
            assertThrows(IllegalArgumentException.class, () -> mapper.map(
                    requestWithCache(CacheRetention.SHORT, "key" + LONE_LOW, null), null));
        }
    }

    @Nested
    class RetryAndProviderError {
        @Test
        void defaultIsSingleAttemptAndStatusTableHasPositiveAndNegative() {
            assertEquals(0, OpenAiRetryPolicy.disabled().maxRetries());
            assertTrue(OpenAiRetry.isRetryableTransport());
            assertTrue(OpenAiRetry.isRetryable(httpError(429, Map.of())));
            assertTrue(OpenAiRetry.isRetryable(httpError(500, Map.of())));
            assertTrue(OpenAiRetry.isRetryable(httpError(599, Map.of())));
            assertFalse(OpenAiRetry.isRetryable(httpError(499, Map.of())));
            assertFalse(OpenAiRetry.isRetryable(httpError(600, Map.of())));
            assertFalse(OpenAiRetry.isRetryable(httpError(401, Map.of())));
            assertTrue(OpenAiRetry.isRetryable(httpError(400, Map.of("x-should-retry", "true"))));
            assertFalse(OpenAiRetry.isRetryable(httpError(429, Map.of("x-should-retry", "false"))));
        }

        @Test
        void delaySourcesAndServerCap() {
            var policy = OpenAiRetryPolicy.builder()
                    .maxRetries(1)
                    .maxServerDelay(Duration.ofSeconds(2))
                    .maxBackoff(Duration.ZERO)
                    .jitterRange(1.0d, 1.0d)
                    .build();
            var now = Instant.parse("2026-01-01T00:00:00Z");
            assertEquals(Duration.ofMillis(250), OpenAiRetry.delayForHttp(
                    httpError(429, Map.of("retry-after-ms", "250")), 0, policy, now, 0).duration());
            assertEquals(Duration.ofSeconds(1), OpenAiRetry.delayForHttp(
                    httpError(429, Map.of("retry-after", "1")), 0, policy, now, 0).duration());
            assertTrue(OpenAiRetry.delayForHttp(
                    httpError(429, Map.of("retry-after-ms", "5000")), 0, policy, now, 0).exceedsServerMax());
            assertEquals(Duration.ZERO, OpenAiRetry.delayForHttp(
                    httpError(429, Map.of("retry-after-ms", "bad", "retry-after", "soon")), 0, policy, now, 0)
                    .duration());
        }

        @Test
        void envelopeVersusFallbackBody() {
            assertEquals("HTTP 400: missing field", OpenAiHttpError.parse(
                    400, java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true),
                    "{\"error\":{\"message\":\"missing field\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    MAPPER).diagnosticMessage());
            assertEquals("HTTP 502: upstream", OpenAiHttpError.parse(
                    502, java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true),
                    "upstream".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    MAPPER).diagnosticMessage());
        }

        private static OpenAiHttpError httpError(int status, Map<String, String> headers) {
            Map<String, java.util.List<String>> mapped = headers.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, e -> java.util.List.of(e.getValue())));
            return OpenAiHttpError.parse(
                    status,
                    java.net.http.HttpHeaders.of(mapped, (a, b) -> true),
                    "{\"error\":{\"message\":\"x\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    MAPPER);
        }
    }

    private static ModelRequest requestWithCache(CacheRetention retention, String cacheKey, String sessionId) {
        return new ModelRequest(REF, "", List.of(), List.of(), ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withPromptCache(
                        new PromptCacheOptions(retention, cacheKey, sessionId)));
    }
}
