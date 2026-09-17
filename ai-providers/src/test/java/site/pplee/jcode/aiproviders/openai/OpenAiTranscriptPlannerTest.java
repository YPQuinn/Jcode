package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.ModelReplayState;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiTranscriptPlannerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef GPT = new ModelRef("openai", "openai-responses", "gpt-5");
    private static final ModelRef GPT_OTHER = new ModelRef("openai", "openai-responses", "gpt-4.1");
    private static final ModelRef ANTHROPIC = new ModelRef("anthropic", "anthropic-messages", "claude");

    private final OpenAiTranscriptPlanner planner = new OpenAiTranscriptPlanner(MAPPER);

    @Test
    void sameModelReplaysEncryptedReasoningBeforeFunctionCall() throws Exception {
        var reasoning = reasoning("think", "rs_1", "enc");
        var callId = OpenAiToolCallIds.encode("call_abc", "fc_1");
        var assistant = assistant(GPT, StopReason.TOOL_CALL, reasoning,
                new Content.ToolCall(callId, "get_weather", MAPPER.createObjectNode()));
        var result = new Message.ToolResultMessage(callId, "get_weather",
                List.of(new Content.Text("ok")), false, T1);

        var plan = planner.plan(List.of(assistant, result), GPT);
        assertTrue(plan.replayedReasoning());
        assertEquals(3, plan.inputItems().size());
        assertEquals("reasoning", plan.inputItems().get(0).get("type").asText());
        assertEquals("enc", plan.inputItems().get(0).get("encrypted_content").asText());
        assertEquals("function_call", plan.inputItems().get(1).get("type").asText());
        assertEquals("fc_1", plan.inputItems().get(1).get("id").asText());
        assertEquals("function_call_output", plan.inputItems().get(2).get("type").asText());
        assertEquals("call_abc", plan.inputItems().get(2).get("call_id").asText());
    }

    @Test
    void sameProviderDifferentModelDropsReasoningAndItemIds() throws Exception {
        var reasoning = reasoning("think", "rs_1", "enc");
        var callId = OpenAiToolCallIds.encode("call_abc", "fc_1");
        var assistant = assistant(GPT, StopReason.TOOL_CALL, reasoning,
                new Content.Text("hi", OpenAiReplayStateCodec.encodeMessage("hi", "msg_keep", "final")),
                new Content.ToolCall(callId, "get_weather", MAPPER.createObjectNode()));

        var plan = planner.plan(List.of(assistant), GPT_OTHER);
        assertFalse(plan.replayedReasoning());
        assertEquals(3, plan.inputItems().size());
        assertEquals("message", plan.inputItems().get(0).get("type").asText());
        assertTrue(plan.inputItems().get(0).get("id").asText().startsWith("msg_jcode_"));
        assertFalse(plan.inputItems().get(0).has("phase"));
        assertEquals("function_call", plan.inputItems().get(1).get("type").asText());
        assertFalse(plan.inputItems().get(1).has("id"));
        assertEquals("function_call_output", plan.inputItems().get(2).get("type").asText());
        assertEquals("No result provided", plan.inputItems().get(2).get("output").asText());
    }

    @Test
    void missingSourceModelIsForeign() throws Exception {
        var reasoning = reasoning("think", "rs_1", "enc");
        var callId = OpenAiToolCallIds.encode("call_abc", "fc_1");
        var assistant = new Message.Assistant(
                List.of(reasoning, new Content.ToolCall(callId, "t", MAPPER.createObjectNode())),
                StopReason.TOOL_CALL, null, Usage.zero(), T1, null);

        var plan = planner.plan(List.of(assistant), GPT);
        assertFalse(plan.replayedReasoning());
        assertEquals("function_call", plan.inputItems().get(0).get("type").asText());
        assertFalse(plan.inputItems().get(0).has("id"));
    }

    @Test
    void errorAssistantAndItsResultsAreDropped() {
        var callId = OpenAiToolCallIds.encode("call_abc", "fc_1");
        var failed = assistant(GPT, StopReason.ERROR,
                new Content.ToolCall(callId, "t", MAPPER.createObjectNode()));
        var result = new Message.ToolResultMessage(callId, "t",
                List.of(new Content.Text("late")), false, T1);
        var user = new Message.User(List.of(new Content.Text("next")), T1);

        var plan = planner.plan(List.of(failed, result, user), GPT);
        assertEquals(1, plan.inputItems().size());
        assertEquals("user", plan.inputItems().get(0).get("role").asText());
    }

    @Test
    void missingResultIsSynthesizedAtUserBoundaryAndTranscriptEnd() {
        var first = OpenAiToolCallIds.encode("call_a", null);
        var second = OpenAiToolCallIds.encode("call_b", null);
        var firstAssistant = assistant(GPT, StopReason.TOOL_CALL,
                new Content.ToolCall(first, "a", MAPPER.createObjectNode()));
        var user = new Message.User(List.of(new Content.Text("go")), T1);
        var secondAssistant = assistant(GPT, StopReason.TOOL_CALL,
                new Content.ToolCall(second, "b", MAPPER.createObjectNode()));

        var plan = planner.plan(List.of(firstAssistant, user, secondAssistant), GPT);
        assertEquals("function_call_output", plan.inputItems().get(1).get("type").asText());
        assertEquals("No result provided", plan.inputItems().get(1).get("output").asText());
        assertEquals("user", plan.inputItems().get(2).get("role").asText());
        assertEquals("function_call_output", plan.inputItems().get(4).get("type").asText());
        assertEquals("call_b", plan.inputItems().get(4).get("call_id").asText());
    }

    @Test
    void orphanToolResultIsDropped() {
        var result = new Message.ToolResultMessage("call_orphan", "t",
                List.of(new Content.Text("x")), false, T1);
        var plan = planner.plan(List.of(result), GPT);
        assertTrue(plan.inputItems().isEmpty());
    }

    @Test
    void malformedReplayStateDropsItemId() {
        var text = new Content.Text("hi", new ModelReplayState(
                OpenAiReplayStateCodec.MESSAGE_FORMAT, "{not-json"));
        var callId = OpenAiToolCallIds.encode("call_abc", "fc_1");
        var assistant = assistant(GPT, StopReason.TOOL_CALL, text,
                new Content.ToolCall(callId, "t", MAPPER.createObjectNode()));

        var plan = planner.plan(List.of(assistant), GPT);
        assertTrue(plan.inputItems().get(0).get("id").asText().startsWith("msg_jcode_"));
        assertFalse(plan.inputItems().get(1).has("id"));
    }

    @Test
    void malformedEncodedToolCallIdStillFails() {
        var assistant = assistant(GPT, StopReason.TOOL_CALL,
                new Content.ToolCall("oai1:!!!", "t", MAPPER.createObjectNode()));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(assistant), GPT));
    }

    @Test
    void emptyToolResultBecomesStablePlaceholder() {
        var callId = OpenAiToolCallIds.encode("call_abc", null);
        var assistant = assistant(GPT, StopReason.TOOL_CALL,
                new Content.ToolCall(callId, "t", MAPPER.createObjectNode()));
        var empty = new Message.ToolResultMessage(callId, "t", List.of(), false, T1);
        var blank = new Message.ToolResultMessage(callId, "t",
                List.of(new Content.Text("   ")), false, T1);

        assertEquals(OpenAiTranscriptPlanner.NO_TOOL_OUTPUT,
                planner.plan(List.of(assistant, empty), GPT).inputItems().get(1).get("output").asText());
        assertEquals(OpenAiTranscriptPlanner.NO_TOOL_OUTPUT,
                planner.plan(List.of(assistant, blank), GPT).inputItems().get(1).get("output").asText());
    }

    @Test
    void textOnlyToolResultKeepsBlockOrder() {
        var callId = OpenAiToolCallIds.encode("call_abc", null);
        var assistant = assistant(GPT, StopReason.TOOL_CALL,
                new Content.ToolCall(callId, "t", MAPPER.createObjectNode()));
        var result = new Message.ToolResultMessage(callId, "t",
                List.of(new Content.Text("first"), new Content.Text("second")), false, T1);

        var output = planner.plan(List.of(assistant, result), GPT).inputItems().get(1);
        assertEquals("function_call_output", output.get("type").asText());
        assertEquals("first\nsecond", output.get("output").asText());
        assertTrue(output.get("output").isTextual());
    }

    @Test
    void visionKeepsToolResultImagesInsideFunctionCallOutput() {
        var image = png();
        var callId = OpenAiToolCallIds.encode("call_abc", null);
        var assistant = assistant(GPT, StopReason.TOOL_CALL,
                new Content.ToolCall(callId, "t", MAPPER.createObjectNode()));
        var imageOnly = new Message.ToolResultMessage(callId, "t", List.of(image), false, T1);
        var mixed = new Message.ToolResultMessage(callId, "t",
                List.of(new Content.Text("caption"), image, new Content.Text("after")), false, T1);

        var imageItems = planner.plan(List.of(assistant, imageOnly), GPT, true).inputItems();
        assertEquals(2, imageItems.size());
        var imageOutput = imageItems.get(1);
        assertEquals("function_call_output", imageOutput.get("type").asText());
        assertEquals("input_image", imageOutput.get("output").get(0).get("type").asText());
        assertEquals("data:image/png;base64,AA==", imageOutput.get("output").get(0).get("image_url").asText());
        assertEquals(2, imageItems.size(), "image must stay inside function_call_output");

        var mixedOutput = planner.plan(List.of(assistant, mixed), GPT, true).inputItems().get(1);
        assertEquals("function_call_output", mixedOutput.get("type").asText());
        assertEquals("input_text", mixedOutput.get("output").get(0).get("type").asText());
        assertEquals("caption", mixedOutput.get("output").get(0).get("text").asText());
        assertEquals("input_image", mixedOutput.get("output").get(1).get("type").asText());
        assertEquals("input_text", mixedOutput.get("output").get(2).get("type").asText());
        assertEquals("after", mixedOutput.get("output").get(2).get("text").asText());
        assertFalse(planner.plan(List.of(assistant, mixed), GPT, true).inputItems().stream()
                .anyMatch(item -> "user".equals(item.path("role").asText())
                        && item.path("content").toString().contains("input_image")));
    }

    @Test
    void visionToolResultOmitsWhitespaceOnlyTextAndKeepsImage() {
        var image = png();
        var callId = OpenAiToolCallIds.encode("call_abc", null);
        var assistant = assistant(GPT, StopReason.TOOL_CALL,
                new Content.ToolCall(callId, "t", MAPPER.createObjectNode()));
        var whitespaceAndImage = new Message.ToolResultMessage(callId, "t",
                List.of(new Content.Text("  \n\t"), image, new Content.Text("   ")), false, T1);

        var output = planner.plan(List.of(assistant, whitespaceAndImage), GPT, true)
                .inputItems().get(1).get("output");
        assertTrue(output.isArray());
        assertEquals(1, output.size());
        assertEquals("input_image", output.get(0).get("type").asText());
        assertEquals("data:image/png;base64,AA==", output.get(0).get("image_url").asText());
        assertFalse(output.toString().contains("input_text"));

        var blankOnly = new Message.ToolResultMessage(callId, "t",
                List.of(new Content.Text("  \n\t"), new Content.Text("   ")), false, T1);
        assertEquals(OpenAiTranscriptPlanner.NO_TOOL_OUTPUT,
                planner.plan(List.of(assistant, blankOnly), GPT, true).inputItems().get(1).get("output").asText());
    }

    @Test
    void nonVisionOmitsUserAndToolImagesInPlace() {
        var image = png();
        var user = new Message.User(List.of(
                new Content.Text("before"), image, new Content.Text("after")), T1);
        var callId = OpenAiToolCallIds.encode("call_abc", null);
        var assistant = assistant(GPT, StopReason.TOOL_CALL,
                new Content.ToolCall(callId, "t", MAPPER.createObjectNode()));
        var result = new Message.ToolResultMessage(callId, "t",
                List.of(new Content.Text("ok"), image), false, T1);

        var userItem = planner.plan(List.of(user), GPT, false).inputItems().get(0);
        assertEquals("user", userItem.get("role").asText());
        assertEquals(1, userItem.get("content").size());
        assertEquals("before\n" + OpenAiTranscriptPlanner.omittedImage("image/png") + "\nafter",
                userItem.get("content").get(0).get("text").asText());

        var toolOutput = planner.plan(List.of(assistant, result), GPT, false).inputItems().get(1);
        assertEquals("ok\n" + OpenAiTranscriptPlanner.omittedImage("image/png"),
                toolOutput.get("output").asText());
        assertTrue(toolOutput.get("output").isTextual());
    }

    @Test
    void visionMapsUserImagesInOriginalOrder() {
        var image = png();
        var user = new Message.User(List.of(
                new Content.Text("look"),
                new Content.Text("closely"),
                image,
                new Content.Text("done")), T1);

        var content = planner.plan(List.of(user), GPT, true).inputItems().get(0).get("content");
        assertEquals(3, content.size());
        assertEquals("input_text", content.get(0).get("type").asText());
        assertEquals("look\nclosely", content.get(0).get("text").asText());
        assertEquals("input_image", content.get(1).get("type").asText());
        assertEquals("data:image/png;base64,AA==", content.get(1).get("image_url").asText());
        assertEquals("done", content.get(2).get("text").asText());
    }

    @Test
    void userThinkingOrToolCallFailsMapping() {
        var thinkingUser = new Message.User(List.of(new Content.Thinking("nope")), T1);
        var toolUser = new Message.User(List.of(
                new Content.ToolCall("c1", "echo", MAPPER.createObjectNode())), T1);
        assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(thinkingUser), GPT));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(toolUser), GPT));
    }

    @Test
    void disallowedToolResultContentFailsMapping() {
        var callId = OpenAiToolCallIds.encode("call_abc", null);
        var assistant = assistant(GPT, StopReason.TOOL_CALL,
                new Content.ToolCall(callId, "t", MAPPER.createObjectNode()));
        var thinking = new Message.ToolResultMessage(callId, "t",
                List.of(new Content.Thinking("hidden")), false, T1);
        var toolCall = new Message.ToolResultMessage(callId, "t",
                List.of(new Content.ToolCall("c2", "other", MAPPER.createObjectNode())), false, T1);

        var thinkingError = assertThrows(IllegalArgumentException.class,
                () -> planner.plan(List.of(assistant, thinking), GPT));
        assertTrue(thinkingError.getMessage().contains("thinking"));
        var toolCallError = assertThrows(IllegalArgumentException.class,
                () -> planner.plan(List.of(assistant, toolCall), GPT));
        assertTrue(toolCallError.getMessage().contains("tool-call"));
    }

    @Test
    void grammarToolCallAndResultReplayAsCustomItemsAndKeepCallPairing() throws Exception {
        var callId = OpenAiToolCallIds.encode("call_1", "ctc_1");
        var arguments = MAPPER.createObjectNode().put("payload", "abc");
        var assistant = assistant(GPT, StopReason.TOOL_CALL,
                new Content.ToolCall(callId, "sample_tool", arguments));
        var result = new Message.ToolResultMessage(callId, "sample_tool",
                List.of(new Content.Text("done")), false, T1);
        var grammar = Map.of("sample_tool", "payload");

        var plan = planner.plan(List.of(assistant, result), GPT, false, grammar);
        assertEquals("custom_tool_call", plan.inputItems().get(0).get("type").asText());
        assertEquals("call_1", plan.inputItems().get(0).get("call_id").asText());
        assertEquals("sample_tool", plan.inputItems().get(0).get("name").asText());
        assertEquals("abc", plan.inputItems().get(0).get("input").asText());
        assertEquals("custom_tool_call_output", plan.inputItems().get(1).get("type").asText());
        assertEquals("call_1", plan.inputItems().get(1).get("call_id").asText());
        assertEquals("done", plan.inputItems().get(1).get("output").asText());

        var plain = planner.plan(List.of(assistant, result), GPT);
        assertEquals("function_call", plain.inputItems().get(0).get("type").asText());
        assertEquals("function_call_output", plain.inputItems().get(1).get("type").asText());

        for (var invalid : List.of(MAPPER.createObjectNode(), MAPPER.createObjectNode().put("payload", 42))) {
            var bad = assistant(GPT, StopReason.TOOL_CALL,
                    new Content.ToolCall(callId, "sample_tool", invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> planner.plan(List.of(bad, result), GPT, false, grammar));
        }
    }

    @Test
    void sameModelGrammarReplayKeepsCustomItemIdAndSynthesizesCustomOutput() throws Exception {
        var reasoning = reasoning("think", "rs_1", "enc");
        var callId = OpenAiToolCallIds.encode("call_1", "ctc_1");
        var assistant = assistant(GPT, StopReason.TOOL_CALL, reasoning,
                new Content.ToolCall(callId, "sample_tool", MAPPER.createObjectNode().put("payload", "abc")));

        var plan = planner.plan(List.of(assistant), GPT, false, Map.of("sample_tool", "payload"));
        assertEquals("custom_tool_call", plan.inputItems().get(1).get("type").asText());
        assertEquals("ctc_1", plan.inputItems().get(1).get("id").asText());
        assertEquals("custom_tool_call_output", plan.inputItems().get(2).get("type").asText());
        assertEquals("No result provided", plan.inputItems().get(2).get("output").asText());
    }

    @Test
    void foreignUnsafeIdsAreHashedConsistently() {
        var unsafe = "id with spaces";
        var assistant = assistant(ANTHROPIC, StopReason.TOOL_CALL,
                new Content.ToolCall(unsafe, "t", MAPPER.createObjectNode()));
        var result = new Message.ToolResultMessage(unsafe, "t",
                List.of(new Content.Text("ok")), false, T1);

        var plan = planner.plan(List.of(assistant, result), GPT);
        String hashed = OpenAiToolCallIds.hashedForeignCallId(unsafe);
        assertEquals(hashed, plan.inputItems().get(0).get("call_id").asText());
        assertEquals(hashed, plan.inputItems().get(1).get("call_id").asText());
        assertFalse(plan.inputItems().get(0).has("id"));
    }

    private static Content.Image png() {
        return new Content.Image("image/png", "AA==");
    }

    private static Content.Thinking reasoning(String text, String id, String encrypted) throws Exception {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "reasoning");
        item.put("id", id);
        item.put("encrypted_content", encrypted);
        return new Content.Thinking(text, OpenAiReplayStateCodec.encodeReasoning(text, item));
    }

    private static Message.Assistant assistant(ModelRef source, StopReason reason, Content... content) {
        return new Message.Assistant(List.of(content), reason, reason.isTerminalFailure() ? "err" : null,
                Usage.zero(), T1, source);
    }
}
