package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import site.pplee.jcode.ai.tool.GrammarSyntax;
import site.pplee.jcode.ai.tool.Requirement;
import site.pplee.jcode.ai.tool.ToolInputConstraint;
import site.pplee.jcode.ai.tool.ToolSpec;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Payload-mapping tests: transcript replay, tool declaration mapping,
 * NullNode normalization, and ThinkingLevel policy.
 */
class OpenAiRequestMapperTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef REF = new ModelRef("openai", "openai-responses", "gpt-4o-mini");

    private final OpenAiRequestMapper mapper = new OpenAiRequestMapper(MAPPER);

    @Test
    void mapsSystemPromptAndUserText() {
        var request = new ModelRequest(REF, "sys prompt",
                List.of(new Message.User(List.of(new Content.Text("hello")), T1)),
                List.of());
        var payload = mapper.map(request, null);

        assertEquals("gpt-4o-mini", payload.get("model").asText());
        assertTrue(payload.get("stream").asBoolean());
        assertFalse(payload.get("store").asBoolean());

        var system = payload.get("input").get(0);
        assertEquals("system", system.get("role").asText());
        assertEquals("sys prompt", system.get("content").get(0).get("text").asText());

        var user = payload.get("input").get(1);
        assertEquals("user", user.get("role").asText());
        assertEquals("hello", user.get("content").get(0).get("text").asText());
        assertFalse(payload.has("max_output_tokens"));
        assertFalse(payload.has("temperature"));
        assertFalse(payload.has("tool_choice"));
        assertFalse(payload.has("prompt_cache_key"));
        assertFalse(payload.has("prompt_cache_retention"));
        assertFalse(payload.has("service_tier"));
    }

    @Test
    void replaysAssistantTextToolCallsAndToolResults() {
        var toolCallId = OpenAiToolCallIds.encode("call_abc", "fc_123");
        var request = new ModelRequest(REF, "",
                List.of(
                        new Message.Assistant(List.of(
                                new Content.Text("first block"),
                                new Content.Text("second block"),
                                new Content.ToolCall(toolCallId, "get_weather",
                                        MAPPER.createObjectNode().put("city", "London"))
                        ), StopReason.TOOL_CALL, null, Usage.zero(), T1),
                        new Message.ToolResultMessage(toolCallId, "get_weather",
                                List.of(new Content.Text("sunny")), false, T1)
                ),
                List.of());
        var payload = mapper.map(request, null);
        var input = payload.get("input");

        var first = input.get(0);
        assertEquals("message", first.get("type").asText());
        assertEquals("assistant", first.get("role").asText());
        assertEquals("completed", first.get("status").asText());
        assertTrue(first.get("id").asText().startsWith("msg_"));
        assertEquals("first block", first.get("content").get(0).get("text").asText());
        assertEquals("second block", input.get(1).get("content").get(0).get("text").asText());
        assertFalse(first.get("id").asText().equals(input.get(1).get("id").asText()));

        var functionCall = input.get(2);
        assertEquals("function_call", functionCall.get("type").asText());
        assertEquals("call_abc", functionCall.get("call_id").asText());
        assertEquals("get_weather", functionCall.get("name").asText());
        assertFalse(functionCall.has("id"), "unknown source must omit function-call item id");
        assertTrue(functionCall.get("arguments").asText().contains("London"));

        var output = input.get(3);
        assertEquals("function_call_output", output.get("type").asText());
        assertEquals("call_abc", output.get("call_id").asText());
        assertEquals("sunny", output.get("output").asText());
    }

    @Test
    void mapsToolSpecsAndNormalizesNullNodeSchema() {
        var request = new ModelRequest(REF, "", List.of(), List.of(ToolSpec.minimal("echo")));
        var payload = mapper.map(request, null);

        var tool = payload.get("tools").get(0);
        assertEquals("function", tool.get("type").asText());
        assertEquals("echo", tool.get("name").asText());
        assertTrue(tool.get("parameters").isObject());
        assertEquals(0, tool.get("parameters").size());
        assertFalse(tool.has("strict"));
        assertFalse(tool.has("format"));
    }

    @Test
    void rejectsNonObjectToolSchema() {
        var spec = new ToolSpec("bad", "desc", MAPPER.getNodeFactory().textNode("not-an-object"));
        var request = new ModelRequest(REF, "", List.of(), List.of(spec));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(request, null));
    }

    @Test
    void defaultThinkingSendsNoReasoningParams() {
        var request = new ModelRequest(REF, "", List.of(), List.of());
        var payload = mapper.map(request, null);
        assertFalse(payload.has("reasoning"));
        assertFalse(payload.has("include"));
    }

    @Test
    void defaultThinkingWithReasoningCapabilitiesSendsIncludeOnly() {
        var capabilities = new OpenAiModelCapabilities(true, Map.of(ThinkingLevel.MEDIUM, "medium"));
        var request = new ModelRequest(REF, "", List.of(), List.of());
        var payload = mapper.map(request, capabilities);
        assertFalse(payload.has("reasoning"));
        assertEquals("reasoning.encrypted_content", payload.get("include").get(0).asText());
    }

    @Test
    void offWithoutCapabilitiesFails() {
        // Unknown model capabilities: OFF must not silently degrade to the provider default.
        var request = new ModelRequest(REF, "", List.of(), List.of(), ThinkingLevel.OFF);
        assertThrows(IllegalArgumentException.class, () -> mapper.map(request, null));
    }

    @Test
    void offForNonReasoningModelSendsNoReasoningParams() {
        var capabilities = new OpenAiModelCapabilities(false, Map.of());
        var request = new ModelRequest(REF, "", List.of(), List.of(), ThinkingLevel.OFF);
        var payload = mapper.map(request, capabilities);
        assertFalse(payload.has("reasoning"));
    }

    @Test
    void offForReasoningModelWithoutMappingFails() {
        var capabilities = new OpenAiModelCapabilities(true, Map.of(ThinkingLevel.MEDIUM, "medium"));
        var request = new ModelRequest(REF, "", List.of(), List.of(), ThinkingLevel.OFF);
        assertThrows(IllegalArgumentException.class, () -> mapper.map(request, capabilities));
    }

    @Test
    void offWithCapabilityMappingSendsReasoningEffort() {
        var capabilities = new OpenAiModelCapabilities(true, Map.of(ThinkingLevel.OFF, "none"));
        var request = new ModelRequest(REF, "", List.of(), List.of(), ThinkingLevel.OFF);
        var payload = mapper.map(request, capabilities);
        assertEquals("none", payload.get("reasoning").get("effort").asText());
        assertFalse(payload.get("reasoning").has("summary"));
        assertFalse(payload.has("include"));
    }

    @Test
    void supportedThinkingLevelMapsThroughCapabilities() {
        var capabilities = new OpenAiModelCapabilities(true, Map.of(ThinkingLevel.MEDIUM, "medium"));
        var request = new ModelRequest(REF, "", List.of(), List.of(), ThinkingLevel.MEDIUM);
        var payload = mapper.map(request, capabilities);
        assertEquals("medium", payload.get("reasoning").get("effort").asText());
        assertEquals("auto", payload.get("reasoning").get("summary").asText());
        assertEquals("reasoning.encrypted_content", payload.get("include").get(0).asText());
    }

    @Test
    void unsupportedThinkingLevelFails() {
        var request = new ModelRequest(REF, "", List.of(), List.of(), ThinkingLevel.MAX);
        assertThrows(IllegalArgumentException.class, () -> mapper.map(request, null));
        var partialCaps = new OpenAiModelCapabilities(true, Map.of(ThinkingLevel.MEDIUM, "medium"));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(request, partialCaps));
    }

    @Test
    void rawToolCallIdIsPassedThroughAsCallIdWhenPaired() {
        var request = new ModelRequest(REF, "",
                List.of(
                        new Message.Assistant(List.of(
                                new Content.ToolCall("call_raw_1", "echo", MAPPER.createObjectNode())
                        ), StopReason.TOOL_CALL, null, Usage.zero(), T1),
                        new Message.ToolResultMessage("call_raw_1", "echo",
                                List.of(new Content.Text("ok")), false, T1)
                ),
                List.of());
        var payload = mapper.map(request, null);
        assertEquals("call_raw_1", payload.get("input").get(0).get("call_id").asText());
        assertEquals("call_raw_1", payload.get("input").get(1).get("call_id").asText());
    }

    @Test
    void orphanToolResultIsDropped() {
        var request = new ModelRequest(REF, "",
                List.of(new Message.ToolResultMessage("call_raw_1", "echo",
                        List.of(new Content.Text("ok")), false, T1)),
                List.of());
        var payload = mapper.map(request, null);
        assertEquals(0, payload.get("input").size());
    }

    @Test
    void malformedEncodedToolCallIdFailsMapping() {
        var request = new ModelRequest(REF, "",
                List.of(new Message.ToolResultMessage("oai1:!!!not-base64!!!", "echo",
                        List.of(new Content.Text("ok")), false, T1)),
                List.of());
        assertThrows(IllegalArgumentException.class, () -> mapper.map(request, null));
    }

    @Test
    void unsafeRawToolCallIdIsHashedAndPaired() {
        var unsafe = "bad id with spaces";
        var request = new ModelRequest(REF, "",
                List.of(
                        new Message.Assistant(List.of(
                                new Content.ToolCall(unsafe, "echo", MAPPER.createObjectNode())
                        ), StopReason.TOOL_CALL, null, Usage.zero(), T1),
                        new Message.ToolResultMessage(unsafe, "echo",
                                List.of(new Content.Text("ok")), false, T1)
                ),
                List.of());
        var payload = mapper.map(request, null);
        String hashed = OpenAiToolCallIds.hashedForeignCallId(unsafe);
        assertEquals(hashed, payload.get("input").get(0).get("call_id").asText());
        assertEquals(hashed, payload.get("input").get(1).get("call_id").asText());
        assertTrue(hashed.length() <= 64);
    }

    @Test
    void twoArgCapabilitiesKeepSystemRoleAndOmitImages() {
        var image = new Content.Image("image/png", "AA==");
        var capabilities = new OpenAiModelCapabilities(true, Map.of(ThinkingLevel.MEDIUM, "medium"));
        var request = new ModelRequest(REF, "sys prompt",
                List.of(new Message.User(List.of(new Content.Text("look"), image), T1)),
                List.of());
        var payload = mapper.map(request, capabilities);

        assertFalse(capabilities.imageInput());
        assertFalse(capabilities.developerRolePreferred());
        assertEquals("system", payload.get("input").get(0).get("role").asText());
        assertEquals("look\n" + OpenAiTranscriptPlanner.omittedImage("image/png"),
                payload.get("input").get(1).get("content").get(0).get("text").asText());
    }

    @Test
    void developerRoleRequiresModelPreferenceAndEndpointSupport() {
        var preferred = new OpenAiModelCapabilities(true, Map.of(), false, true);
        var request = new ModelRequest(REF, "sys prompt", List.of(), List.of());

        assertEquals("developer", mapper.map(request, preferred).get("input").get(0).get("role").asText());
        assertEquals("system", mapper.map(request, preferred, OpenAiResponsesCompatibility.forceSystem())
                .get("input").get(0).get("role").asText());
        assertEquals("system", mapper.map(request, OpenAiModelCapabilities.noReasoning())
                .get("input").get(0).get("role").asText());
        assertEquals(0, mapper.map(new ModelRequest(REF, "  ", List.of(), List.of()), preferred)
                .get("input").size());
    }

    @Test
    void visionUserAndToolImagesStayOnTheirItems() {
        var image = new Content.Image("image/png", "AA==");
        var toolCallId = OpenAiToolCallIds.encode("call_abc", "fc_1");
        var capabilities = new OpenAiModelCapabilities(false, Map.of(), true, false);
        var request = new ModelRequest(REF, "sys",
                List.of(
                        new Message.User(List.of(new Content.Text("see"), image), T1),
                        new Message.Assistant(List.of(
                                new Content.ToolCall(toolCallId, "look", MAPPER.createObjectNode())
                        ), StopReason.TOOL_CALL, null, Usage.zero(), T1),
                        new Message.ToolResultMessage(toolCallId, "look",
                                List.of(image), false, T1)
                ),
                List.of());
        var input = mapper.map(request, capabilities).get("input");

        assertEquals("system", input.get(0).get("role").asText());
        assertEquals("input_image", input.get(1).get("content").get(1).get("type").asText());
        assertEquals("data:image/png;base64,AA==",
                input.get(1).get("content").get(1).get("image_url").asText());
        assertEquals("function_call_output", input.get(3).get("type").asText());
        assertEquals("input_image", input.get(3).get("output").get(0).get("type").asText());
        assertEquals(4, input.size());
    }

    @Test
    void assistantImageContentFailsMapping() {
        var request = new ModelRequest(REF, "",
                List.of(new Message.Assistant(List.of(new Content.Image("image/png", "AA==")),
                        StopReason.STOP, null, Usage.zero(), T1)),
                List.of());
        var error = assertThrows(IllegalArgumentException.class,
                () -> mapper.map(request, new OpenAiModelCapabilities(false, Map.of(), true, false)));
        assertTrue(error.getMessage().contains("assistant image"));
    }

    @Test
    void replayOmitsUnusableDecodedItemId() {
        // Encoded id whose item id is not fc_-prefixed: the item id must be omitted, not sanitized.
        var toolCallId = OpenAiToolCallIds.encode("call_abc", "msg_123");
        var request = new ModelRequest(REF, "",
                List.of(new Message.Assistant(List.of(
                        new Content.ToolCall(toolCallId, "get_weather", MAPPER.createObjectNode())
                ), StopReason.TOOL_CALL, null, Usage.zero(), T1)),
                List.of());
        var payload = mapper.map(request, null);
        var functionCall = payload.get("input").get(0);
        assertEquals("call_abc", functionCall.get("call_id").asText());
        assertFalse(functionCall.has("id"));
    }

    @Test
    void maxOutputTokensClampAndOmitUnset() {
        var unset = mapper.map(new ModelRequest(REF, "", List.of(), List.of()), null);
        assertFalse(unset.has("max_output_tokens"));

        assertEquals(16, mappedTokens(1));
        assertEquals(16, mappedTokens(15));
        assertEquals(16, mappedTokens(16));
        assertEquals(128, mappedTokens(128));
    }

    @Test
    void maxOutputTokensFailsWhenEndpointRejectsIt() {
        var request = requestWith(ModelRequestOptions.defaults().withMaxOutputTokens(32));
        var conservative = new OpenAiResponsesCompatibility(true);
        var error = assertThrows(IllegalArgumentException.class,
                () -> mapper.map(request, null, conservative));
        assertTrue(error.getMessage().contains("maxOutputTokens"));
    }

    @Test
    void temperatureRequiresModelCapability() {
        var request = requestWith(ModelRequestOptions.defaults().withTemperature(0.4d));
        var error = assertThrows(IllegalArgumentException.class, () -> mapper.map(request, null));
        assertTrue(error.getMessage().contains("temperature"));

        var payload = mapper.map(request, samplingCapabilities());
        assertEquals(0.4d, payload.get("temperature").asDouble());
        var omitted = mapper.map(new ModelRequest(REF, "", List.of(), List.of()), samplingCapabilities());
        assertFalse(omitted.has("temperature"));
    }

    @Test
    void toolChoiceModesAndUnknownTool() {
        var echo = ToolSpec.minimal("echo");
        var auto = new ModelRequest(REF, "", List.of(), List.of(echo), ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withToolChoice(ToolChoice.auto()));
        assertFalse(mapper.map(auto, samplingCapabilities()).has("tool_choice"));

        var none = new ModelRequest(REF, "", List.of(), List.of(), ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withToolChoice(ToolChoice.none()));
        assertEquals("none", mapper.map(none, samplingCapabilities()).get("tool_choice").asText());

        var required = new ModelRequest(REF, "", List.of(), List.of(echo), ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withToolChoice(ToolChoice.required()));
        assertEquals("required", mapper.map(required, samplingCapabilities()).get("tool_choice").asText());

        var specific = new ModelRequest(REF, "", List.of(), List.of(echo), ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withToolChoice(ToolChoice.specific("echo")));
        var choice = mapper.map(specific, samplingCapabilities()).get("tool_choice");
        assertEquals("function", choice.get("type").asText());
        assertEquals("echo", choice.get("name").asText());

        var missing = new ModelRequest(REF, "", List.of(), List.of(echo), ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withToolChoice(ToolChoice.specific("other")));
        var missingError = assertThrows(IllegalArgumentException.class,
                () -> mapper.map(missing, samplingCapabilities()));
        assertTrue(missingError.getMessage().contains("not declared"));
        assertFalse(missingError.getMessage().contains("other"));

        var requiredEmpty = requestWith(ModelRequestOptions.defaults().withToolChoice(ToolChoice.required()));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(requiredEmpty, samplingCapabilities()));

        var unsupported = requestWith(ModelRequestOptions.defaults().withToolChoice(ToolChoice.none()));
        var unsupportedError = assertThrows(IllegalArgumentException.class, () -> mapper.map(unsupported, null));
        assertTrue(unsupportedError.getMessage().contains("tool choice"));
    }

    @Test
    void specificToolChoiceFollowsResolvedGrammarNotConstraintType() throws Exception {
        var grammar = new ToolSpec("sample_tool", "Sample tool", grammarSchema("payload"),
                new ToolInputConstraint.Grammar(Map.of(GrammarSyntax.LARK, "start: /x/"), Requirement.PREFER));
        var request = new ModelRequest(REF, "", List.of(), List.of(grammar), ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withToolChoice(ToolChoice.specific("sample_tool")));

        var custom = mapper.map(request, samplingCapabilities()).get("tool_choice");
        assertEquals("custom", custom.get("type").asText());
        assertEquals("sample_tool", custom.get("name").asText());

        var fallback = mapper.map(request, samplingCapabilities(), new OpenAiResponsesCompatibility(true))
                .get("tool_choice");
        assertEquals("function", fallback.get("type").asText());
        assertEquals("sample_tool", fallback.get("name").asText());
    }

    @Test
    void promptCacheRetentionModesAndCodePointClamp() {
        var defaultPayload = mapper.map(requestWith(ModelRequestOptions.defaults()), null);
        assertFalse(defaultPayload.has("prompt_cache_key"));
        assertFalse(defaultPayload.has("prompt_cache_retention"));

        var none = requestWith(ModelRequestOptions.defaults().withPromptCache(
                PromptCacheOptions.none().withCacheKey("ignored").withSessionAffinityId("session")));
        var nonePayload = mapper.map(none, null);
        assertFalse(nonePayload.has("prompt_cache_key"));
        assertFalse(nonePayload.has("prompt_cache_retention"));

        var defaultWithKey = requestWith(ModelRequestOptions.defaults().withPromptCache(
                PromptCacheOptions.defaults().withCacheKey("cache-default")));
        var defaultWithKeyPayload = mapper.map(defaultWithKey, null);
        assertEquals("cache-default", defaultWithKeyPayload.get("prompt_cache_key").asText());
        assertFalse(defaultWithKeyPayload.has("prompt_cache_retention"));

        var shortKey = requestWith(ModelRequestOptions.defaults().withPromptCache(
                PromptCacheOptions.defaults().withRetention(CacheRetention.SHORT).withCacheKey("cache-short")));
        assertEquals("cache-short", mapper.map(shortKey, null).get("prompt_cache_key").asText());
        assertFalse(mapper.map(shortKey, null).has("prompt_cache_retention"));

        var longKey = requestWith(ModelRequestOptions.defaults().withPromptCache(
                PromptCacheOptions.defaults().withRetention(CacheRetention.LONG).withCacheKey("cache-long")));
        var longPayload = mapper.map(longKey, null);
        assertEquals("cache-long", longPayload.get("prompt_cache_key").asText());
        assertEquals("24h", longPayload.get("prompt_cache_retention").asText());

        String emoji = "👍";
        var oversize = "x".repeat(63) + emoji + "y";
        var clamped = requestWith(ModelRequestOptions.defaults().withPromptCache(
                PromptCacheOptions.defaults().withRetention(CacheRetention.SHORT).withCacheKey(oversize)));
        String sent = mapper.map(clamped, null).get("prompt_cache_key").asText();
        assertEquals(64, sent.codePointCount(0, sent.length()));
        assertTrue(sent.endsWith(emoji));
        assertFalse(sent.contains("y"));
        assertEquals(63 + emoji.length(), sent.length());

        var conservative = new OpenAiResponsesCompatibility(true);
        var defaultKeyUnsupported = requestWith(ModelRequestOptions.defaults().withPromptCache(
                PromptCacheOptions.defaults().withCacheKey("cache-default")));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(defaultKeyUnsupported, null, conservative));
        var shortUnsupported = requestWith(ModelRequestOptions.defaults().withPromptCache(
                PromptCacheOptions.defaults().withRetention(CacheRetention.SHORT)));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(shortUnsupported, null, conservative));
        var longUnsupported = requestWith(ModelRequestOptions.defaults().withPromptCache(
                PromptCacheOptions.defaults().withRetention(CacheRetention.LONG)));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(longUnsupported, null, conservative));
    }

    @Test
    void serviceTierIsWrittenWhenConfigured() {
        var request = new ModelRequest(REF, "", List.of(), List.of());
        assertFalse(mapper.map(request, null).has("service_tier"));
        assertEquals("priority", mapper.map(request, null, OpenAiResponsesCompatibility.openai(),
                OpenAiServiceTier.PRIORITY).get("service_tier").asText());
    }

    @Test
    void jsonSchemaStrictIsEmittedWhenSupportedAndFallsBackWhenPreferred() throws Exception {
        var schema = MAPPER.readTree("""
                {"type":"object","properties":{"path":{"type":"string"},"offset":{"type":"number"}},"required":["path"]}
                """);
        var prefer = new ToolSpec("read", "read file", schema,
                new ToolInputConstraint.JsonSchema(Requirement.PREFER));
        var require = new ToolSpec("read", "read file", schema,
                new ToolInputConstraint.JsonSchema(Requirement.REQUIRE));

        var supported = mapper.map(new ModelRequest(REF, "", List.of(), List.of(prefer)), null);
        var tool = supported.get("tools").get(0);
        assertEquals("function", tool.get("type").asText());
        assertTrue(tool.get("strict").asBoolean());
        assertEquals(false, tool.get("parameters").get("additionalProperties").asBoolean());
        assertEquals("offset", tool.get("parameters").get("required").get(1).asText());
        assertEquals("null", tool.get("parameters").get("properties").get("offset").get("anyOf").get(1).get("type").asText());
        assertFalse(schema.has("additionalProperties"), "original schema must stay unchanged");

        var conservative = new OpenAiResponsesCompatibility(true);
        var fallback = mapper.map(new ModelRequest(REF, "", List.of(), List.of(prefer)), null, conservative)
                .get("tools").get(0);
        assertEquals("function", fallback.get("type").asText());
        assertFalse(fallback.has("strict"));
        assertEquals(schema, fallback.get("parameters"));

        assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(), List.of(require)), null, conservative));
    }

    @Test
    void jsonSchemaPreferFallsBackWhenSchemaCannotBeMadeStrict() throws Exception {
        var unsupported = MAPPER.readTree("""
                {"type":"object","properties":{"child":{"$ref":"https://example.com/child.json"}},"required":["child"]}
                """);
        var prefer = new ToolSpec("bad", "desc", unsupported,
                new ToolInputConstraint.JsonSchema(Requirement.PREFER));
        var require = new ToolSpec("bad", "desc", unsupported,
                new ToolInputConstraint.JsonSchema(Requirement.REQUIRE));

        var fallback = mapper.map(new ModelRequest(REF, "", List.of(), List.of(prefer)), null).get("tools").get(0);
        assertFalse(fallback.has("strict"));
        assertEquals(unsupported, fallback.get("parameters"));
        var error = assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(), List.of(require)), null));
        assertTrue(error.getMessage().contains("$ref"));
    }

    @Test
    void grammarSelectsLarkOverRegexAndFallsBackWhenUnsupported() throws Exception {
        var schema = grammarSchema("payload");
        var both = new ToolSpec("sample_tool", "Sample tool", schema,
                new ToolInputConstraint.Grammar(Map.of(
                        GrammarSyntax.LARK, "start: /[a-z]+/",
                        GrammarSyntax.REGEX, "[0-9]+"
                ), Requirement.PREFER));
        var regexOnly = new ToolSpec("sample_tool", "Sample tool", schema,
                new ToolInputConstraint.Grammar(Map.of(GrammarSyntax.REGEX, "[0-9]+"), Requirement.PREFER));
        var require = new ToolSpec("sample_tool", "Sample tool", schema,
                new ToolInputConstraint.Grammar(Map.of(GrammarSyntax.LARK, "start: /[a-z]+/"), Requirement.REQUIRE));

        var lark = mapper.map(new ModelRequest(REF, "", List.of(), List.of(both)), null).get("tools").get(0);
        assertEquals("custom", lark.get("type").asText());
        assertEquals("lark", lark.get("format").get("syntax").asText());
        assertEquals("start: /[a-z]+/", lark.get("format").get("definition").asText());
        assertFalse(lark.has("parameters"));

        var regex = mapper.map(new ModelRequest(REF, "", List.of(), List.of(regexOnly)), null).get("tools").get(0);
        assertEquals("regex", regex.get("format").get("syntax").asText());

        var conservative = new OpenAiResponsesCompatibility(true);
        var fallback = mapper.map(new ModelRequest(REF, "", List.of(), List.of(both)), null, conservative)
                .get("tools").get(0);
        assertEquals("function", fallback.get("type").asText());
        assertFalse(fallback.has("strict"));
        assertEquals(schema, fallback.get("parameters"));

        assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(), List.of(require)), null, conservative));
    }

    @Test
    void grammarSupportedButMalformedOrMissingVariantFailsEvenWhenPreferred() throws Exception {
        var preferEmpty = new ToolSpec("sample_tool", "Sample tool", grammarSchema("payload"),
                new ToolInputConstraint.Grammar(Map.of(), Requirement.PREFER));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(), List.of(preferEmpty)), null));

        var notObject = new ToolSpec("sample_tool", "Sample tool", MAPPER.readTree("{\"type\":\"string\"}"),
                new ToolInputConstraint.Grammar(Map.of(GrammarSyntax.LARK, "start: /x/"), Requirement.PREFER));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(), List.of(notObject)), null));

        var twoRequired = new ToolSpec("sample_tool", "Sample tool", MAPPER.readTree("""
                {"type":"object","properties":{"a":{"type":"string"},"b":{"type":"string"}},"required":["a","b"]}
                """), new ToolInputConstraint.Grammar(Map.of(GrammarSyntax.LARK, "start: /x/"), Requirement.PREFER));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(), List.of(twoRequired)), null));

        var missingProperty = new ToolSpec("sample_tool", "Sample tool", MAPPER.readTree("""
                {"type":"object","properties":{},"required":["payload"]}
                """), new ToolInputConstraint.Grammar(Map.of(GrammarSyntax.LARK, "start: /x/"), Requirement.PREFER));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(), List.of(missingProperty)), null));

        var notString = new ToolSpec("sample_tool", "Sample tool", MAPPER.readTree("""
                {"type":"object","properties":{"payload":{"type":"number"}},"required":["payload"]}
                """), new ToolInputConstraint.Grammar(Map.of(GrammarSyntax.LARK, "start: /x/"), Requirement.PREFER));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(), List.of(notString)), null));
    }

    private static ModelRequest requestWith(ModelRequestOptions options) {
        return new ModelRequest(REF, "", List.of(), List.of(), ThinkingLevel.PROVIDER_DEFAULT, options);
    }

    private int mappedTokens(int requested) {
        var request = requestWith(ModelRequestOptions.defaults().withMaxOutputTokens(requested));
        return mapper.map(request, null).get("max_output_tokens").asInt();
    }

    private static OpenAiModelCapabilities samplingCapabilities() {
        return new OpenAiModelCapabilities(false, Map.of(), false, false, true, true);
    }

    private static com.fasterxml.jackson.databind.JsonNode grammarSchema(String property) throws Exception {
        return MAPPER.readTree("""
                {"type":"object","properties":{"%s":{"type":"string"}},"required":["%s"]}
                """.formatted(property, property));
    }
}
