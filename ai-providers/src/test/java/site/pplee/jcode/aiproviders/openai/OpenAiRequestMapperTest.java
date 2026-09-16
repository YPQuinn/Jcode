package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
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
}
