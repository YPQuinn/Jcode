package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.client.CacheRetention;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.client.PromptCacheOptions;
import site.pplee.jcode.ai.client.ToolChoice;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.ModelReplayState;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.tool.GrammarSyntax;
import site.pplee.jcode.ai.tool.Requirement;
import site.pplee.jcode.ai.tool.ToolInputConstraint;
import site.pplee.jcode.ai.tool.ToolSpec;
import site.pplee.jcode.ai.util.UnicodeSanitizer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OAI-017 request-local Unicode sanitation and identity-failure tests.
 * Canonical Message/ToolSpec/JsonNode objects must stay unchanged.
 */
class OpenAiUnicodeSanitizerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef REF = new ModelRef("openai", "openai-responses", "gpt-4o-mini");
    private static final String LONE_HIGH = "\uD83D";
    private static final String LONE_LOW = "\uDC4D";
    private static final String EMOJI = "\uD83D\uDC4D";
    private static final String COMBINING = "e\u0301";

    private final OpenAiRequestMapper mapper = new OpenAiRequestMapper(MAPPER);

    @Test
    void sanitizesPromptMessagesToolResultSchemaArgumentsAndGrammar() throws Exception {
        String dirty = "hello " + LONE_HIGH + " world " + EMOJI;
        String clean = "hello  world " + EMOJI;
        ObjectNode schema = (ObjectNode) MAPPER.readTree(
                "{\"type\":\"object\",\"properties\":{\"payload\":{\"type\":\"string\",\"description\":\"d\"}},"
                        + "\"required\":[\"payload\"]}");
        ((ObjectNode) ((ObjectNode) schema.get("properties")).get("payload"))
                .put("description", "desc " + LONE_LOW + COMBINING);
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("city", "London" + LONE_HIGH);
        arguments.set("tags", MAPPER.createArrayNode().add("ok").add("x" + LONE_LOW));
        arguments.set("nested", MAPPER.createObjectNode().put("note", "n" + LONE_HIGH));
        var callId = OpenAiToolCallIds.encode("call_abc", "fc_1");
        var image = new Content.Image("image/png", "AA==");
        var user = new Message.User(List.of(new Content.Text(dirty), image), T1);
        var assistant = new Message.Assistant(List.of(
                new Content.Text("said " + LONE_LOW + EMOJI),
                new Content.ToolCall(callId, "lookup", arguments)
        ), StopReason.TOOL_CALL, null, Usage.zero(), T1, REF);
        var toolResult = new Message.ToolResultMessage(callId, "lookup",
                List.of(new Content.Text("result " + LONE_HIGH + COMBINING)), false, T1);
        var grammar = new ToolSpec("sample_tool", "desc " + LONE_HIGH,
                schema, new ToolInputConstraint.Grammar(
                Map.of(GrammarSyntax.LARK, "start: /x/" + LONE_LOW), Requirement.PREFER));
        var function = new ToolSpec("lookup", "look " + LONE_LOW, schema);

        var request = new ModelRequest(REF, "sys " + LONE_HIGH + EMOJI,
                List.of(user, assistant, toolResult), List.of(function, grammar));
        var payload = mapper.map(request, new OpenAiModelCapabilities(false, Map.of(), true, false));

        assertEquals("sys " + EMOJI, payload.get("input").get(0).get("content").get(0).get("text").asText());
        assertEquals(clean, payload.get("input").get(1).get("content").get(0).get("text").asText());
        assertEquals("data:image/png;base64,AA==",
                payload.get("input").get(1).get("content").get(1).get("image_url").asText());
        assertEquals("said " + EMOJI, payload.get("input").get(2).get("content").get(0).get("text").asText());
        JsonNode sentArgs = MAPPER.readTree(payload.get("input").get(3).get("arguments").asText());
        assertEquals("London", sentArgs.get("city").asText());
        assertEquals("x", sentArgs.get("tags").get(1).asText());
        assertEquals("n", sentArgs.get("nested").get("note").asText());
        assertEquals("result " + COMBINING, payload.get("input").get(4).get("output").asText());
        assertEquals("look ", payload.get("tools").get(0).get("description").asText());
        assertEquals("desc " + COMBINING,
                payload.get("tools").get(0).get("parameters").get("properties").get("payload").get("description").asText());
        assertEquals("desc ", payload.get("tools").get(1).get("description").asText());
        assertEquals("start: /x/", payload.get("tools").get(1).get("format").get("definition").asText());

        assertSame(dirty, ((Content.Text) user.content().getFirst()).text());
        assertSame(arguments, ((Content.ToolCall) assistant.content().get(1)).arguments());
        assertEquals("London" + LONE_HIGH, arguments.get("city").asText());
        assertSame(schema, function.parameters());
        assertEquals("desc " + LONE_LOW + COMBINING,
                schema.get("properties").get("payload").get("description").asText());
        assertNotSame(schema, payload.get("tools").get(0).get("parameters"));
    }

    @Test
    void validStringsKeepInstanceAndRoundTripAfterJsonDecode() throws Exception {
        String legal = "BMP café " + EMOJI + " " + COMBINING;
        assertSame(legal, UnicodeSanitizer.removeUnpairedSurrogates(legal));
        var request = new ModelRequest(REF, legal,
                List.of(new Message.User(List.of(new Content.Text(legal)), T1)),
                List.of());
        var payload = mapper.map(request, null);
        byte[] json = MAPPER.writeValueAsBytes(payload);
        JsonNode decoded = MAPPER.readTree(json);
        String prompt = decoded.get("input").get(0).get("content").get(0).get("text").asText();
        String user = decoded.get("input").get(1).get("content").get(0).get("text").asText();
        assertEquals(legal, prompt);
        assertEquals(legal, user);
        assertEquals(legal, new String(prompt.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    @Test
    void identityFieldsFailWithoutEchoingTheMalformedValue() {
        String dirtyName = "echo" + LONE_HIGH;
        IllegalArgumentException toolName = assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "sys", List.of(), List.of(ToolSpec.minimal(dirtyName))), null));
        assertEquals("malformed UTF-16 in tool name", toolName.getMessage());
        assertFalse(toolName.getMessage().contains(LONE_HIGH));

        var dirtyId = "call" + LONE_LOW;
        IllegalArgumentException callId = assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(new Message.Assistant(List.of(
                        new Content.ToolCall(dirtyId, "echo", MAPPER.createObjectNode())
                ), StopReason.TOOL_CALL, null, Usage.zero(), T1)), List.of()), null));
        assertEquals("malformed UTF-16 in tool-call id", callId.getMessage());
        assertFalse(callId.getMessage().contains(LONE_LOW));

        IllegalArgumentException model = assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(new ModelRef("openai", "openai-responses", "gpt" + LONE_HIGH),
                        "sys", List.of(), List.of()), null));
        assertEquals("malformed UTF-16 in model id", model.getMessage());

        IllegalArgumentException provider = assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(new ModelRef("open" + LONE_LOW, "openai-responses", "gpt-4o-mini"),
                        "sys", List.of(), List.of()), null));
        assertEquals("malformed UTF-16 in provider id", provider.getMessage());

        IllegalArgumentException api = assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(new ModelRef("openai", "api" + LONE_HIGH, "gpt-4o-mini"),
                        "sys", List.of(), List.of()), null));
        assertEquals("malformed UTF-16 in api id", api.getMessage());

        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("bad" + LONE_HIGH).put("type", "string");
        IllegalArgumentException field = assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(), List.of(new ToolSpec("echo", "d", schema))), null));
        assertEquals("malformed UTF-16 in JSON object field name", field.getMessage());
        assertFalse(field.getMessage().contains(LONE_HIGH));
    }

    @Test
    void specificToolChoiceAndHeaderIdentitiesFailClosed() {
        String dirty = "echo" + LONE_HIGH;
        var sampling = new OpenAiModelCapabilities(false, Map.of(), false, false, true, true);
        IllegalArgumentException choice = assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(), List.of(ToolSpec.minimal("echo")),
                        ThinkingLevel.PROVIDER_DEFAULT,
                        ModelRequestOptions.defaults().withToolChoice(ToolChoice.specific(dirty))),
                sampling));
        assertEquals("malformed UTF-16 in tool name", choice.getMessage());

        IllegalArgumentException headerName = assertThrows(IllegalArgumentException.class,
                () -> OpenAiHeaders.builder().header("X-Tr" + LONE_HIGH, "ok"));
        assertEquals("header name contains malformed UTF-16", headerName.getMessage());
        assertFalse(headerName.getMessage().contains(LONE_HIGH));

        IllegalArgumentException headerValue = assertThrows(IllegalArgumentException.class,
                () -> OpenAiHeaders.builder().header("X-Trace", "secret" + LONE_LOW));
        assertEquals("header value contains malformed UTF-16", headerValue.getMessage());
        assertFalse(headerValue.getMessage().contains("secret"));
        assertFalse(headerValue.getMessage().contains(LONE_LOW));
    }

    @Test
    void malformedToolNameFailsBeforeGrammarResolutionWithoutEchoingValue() throws Exception {
        String dirtyName = "sample" + LONE_HIGH;
        var schema = MAPPER.readTree(
                "{\"type\":\"object\",\"properties\":{\"payload\":{\"type\":\"string\"}},\"required\":[\"payload\"]}");
        var requireGrammar = new ToolSpec(dirtyName, "g", schema,
                new ToolInputConstraint.Grammar(Map.of(GrammarSyntax.LARK, "start: /x/"), Requirement.REQUIRE));
        IllegalArgumentException properties = assertThrows(IllegalArgumentException.class,
                () -> OpenAiConstrainedSampling.grammarInputProperties(List.of(requireGrammar), false));
        assertEquals("malformed UTF-16 in tool name", properties.getMessage());
        assertFalse(properties.getMessage().contains(dirtyName));
        assertFalse(properties.getMessage().contains(LONE_HIGH));
        assertFalse(properties.getMessage().contains("grammar"));

        var conservative = new OpenAiResponsesCompatibility(true);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(), List.of(requireGrammar)), null, conservative));
        assertEquals("malformed UTF-16 in tool name", error.getMessage());
        assertFalse(error.getMessage().contains(dirtyName));
        assertFalse(error.getMessage().contains(LONE_HIGH));
        assertFalse(error.getMessage().contains("grammar"));
    }

    @Test
    void promptCacheKeyIsIdentityAndDoesNotSanitize() {
        String dirty = "cache" + LONE_HIGH + EMOJI;
        var options = ModelRequestOptions.defaults().withPromptCache(
                new PromptCacheOptions(CacheRetention.SHORT, dirty, "session-1"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(), List.of(), ThinkingLevel.PROVIDER_DEFAULT, options), null));
        assertEquals("malformed UTF-16 in prompt cache key", error.getMessage());
        assertFalse(error.getMessage().contains(dirty));
        assertFalse(error.getMessage().contains(LONE_HIGH));
        assertEquals(dirty, options.promptCache().cacheKey());
        assertEquals("session-1", options.promptCache().sessionAffinityId());
    }

    @Test
    void replayedGrammarCustomInputIsSanitizedWithoutMutatingArguments() throws Exception {
        var callId = OpenAiToolCallIds.encode("call_1", "ctc_1");
        String dirty = "hello " + LONE_HIGH + " world " + EMOJI;
        ObjectNode arguments = MAPPER.createObjectNode().put("payload", dirty);
        var assistant = new Message.Assistant(List.of(
                new Content.ToolCall(callId, "sample_tool", arguments)
        ), StopReason.TOOL_CALL, null, Usage.zero(), T1, REF);
        var result = new Message.ToolResultMessage(callId, "sample_tool",
                List.of(new Content.Text("done")), false, T1);
        var schema = MAPPER.readTree(
                "{\"type\":\"object\",\"properties\":{\"payload\":{\"type\":\"string\"}},\"required\":[\"payload\"]}");
        var request = new ModelRequest(REF, "", List.of(assistant, result), List.of(new ToolSpec(
                "sample_tool", "g", schema,
                new ToolInputConstraint.Grammar(Map.of(GrammarSyntax.LARK, "start: /x/"), Requirement.PREFER))));

        var payload = mapper.map(request, null);
        assertEquals("custom_tool_call", payload.get("input").get(0).get("type").asText());
        assertEquals("hello  world " + EMOJI, payload.get("input").get(0).get("input").asText());
        assertSame(arguments, ((Content.ToolCall) assistant.content().getFirst()).arguments());
        assertEquals(dirty, arguments.get("payload").asText());
    }

    @Test
    void replayItemIdAndPhaseAreIdentitiesAndLeaveReplayStateUnchanged() {
        String dirtyId = "msg" + LONE_HIGH;
        ModelReplayState idState = OpenAiReplayStateCodec.encodeMessage("hello", dirtyId, "final");
        var idText = new Content.Text("hello", idState);
        IllegalArgumentException idError = assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(new Message.Assistant(
                        List.of(idText), StopReason.STOP, null, Usage.zero(), T1, REF)), List.of()), null));
        assertEquals("malformed UTF-16 in replay item id", idError.getMessage());
        assertFalse(idError.getMessage().contains(dirtyId));
        assertSame(idState, idText.replayState());

        String dirtyPhase = "final" + LONE_LOW;
        ModelReplayState phaseState = OpenAiReplayStateCodec.encodeMessage("hello", "msg_keep", dirtyPhase);
        var phaseText = new Content.Text("hello", phaseState);
        IllegalArgumentException phaseError = assertThrows(IllegalArgumentException.class, () -> mapper.map(
                new ModelRequest(REF, "", List.of(new Message.Assistant(
                        List.of(phaseText), StopReason.STOP, null, Usage.zero(), T1, REF)), List.of()), null));
        assertEquals("malformed UTF-16 in replay phase", phaseError.getMessage());
        assertFalse(phaseError.getMessage().contains(dirtyPhase));
        assertSame(phaseState, phaseText.replayState());

        ModelReplayState valid = OpenAiReplayStateCodec.encodeMessage("hello", "msg_keep", "final");
        var validText = new Content.Text("hello", valid);
        var payload = mapper.map(new ModelRequest(REF, "", List.of(new Message.Assistant(
                List.of(validText), StopReason.STOP, null, Usage.zero(), T1, REF)), List.of()), null);
        assertEquals("msg_keep", payload.get("input").get(0).get("id").asText());
        assertEquals("final", payload.get("input").get(0).get("phase").asText());
        assertSame(valid, validText.replayState());
    }
}
