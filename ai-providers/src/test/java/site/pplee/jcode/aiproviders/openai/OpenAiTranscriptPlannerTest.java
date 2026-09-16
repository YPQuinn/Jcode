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
