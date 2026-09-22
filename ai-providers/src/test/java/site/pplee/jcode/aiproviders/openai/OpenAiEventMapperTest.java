package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.ModelFailureKind;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Responses event → Jcode event mapping tests: text/thinking/tool-call
 * start-delta-end ordering, partial accumulation, usage formula, stop-reason
 * mapping and terminal handling.
 */
class OpenAiEventMapperTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ModelRef SOURCE = new ModelRef("openai", "openai-responses", "gpt-4o-mini");

    private static OpenAiEventMapper mapper() {
        return new OpenAiEventMapper(SOURCE);
    }

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void mapsTextStreamWithAccumulatedPartialsAndUsage() throws Exception {
        var mapper = mapper();
        var all = new ArrayList<AssistantMessageEvent>();
        all.addAll(mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":[],\"status\":\"in_progress\"}}")));
        all.addAll(mapper.onEvent("response.output_text.delta", json(
                "{\"output_index\":0,\"delta\":\"Hel\"}")));
        all.addAll(mapper.onEvent("response.output_text.delta", json(
                "{\"output_index\":0,\"delta\":\"lo\"}")));
        all.addAll(mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\"}],\"status\":\"completed\"}}")));
        all.addAll(mapper.onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15,\"input_tokens_details\":{\"cached_tokens\":2}}}}")));

        assertEquals(5, all.size());
        assertInstanceOf(AssistantMessageEvent.TextStart.class, all.get(0));

        var firstDelta = (AssistantMessageEvent.TextDelta) all.get(1);
        assertEquals("Hel", firstDelta.delta());
        assertEquals("Hel", ((Content.Text) firstDelta.partial().content().get(0)).text());

        var secondDelta = (AssistantMessageEvent.TextDelta) all.get(2);
        assertEquals("Hello", ((Content.Text) secondDelta.partial().content().get(0)).text());

        var end = (AssistantMessageEvent.TextEnd) all.get(3);
        assertEquals("Hello", end.content());

        var done = (AssistantMessageEvent.Done) all.get(4);
        assertEquals(StopReason.STOP, done.reason());
        assertEquals("Hello", ((Content.Text) done.message().content().get(0)).text());
        assertEquals(8, done.message().usage().input());
        assertEquals(2, done.message().usage().cacheRead());
        assertEquals(0, done.message().usage().cacheWrite());
        assertEquals(5, done.message().usage().output());
        assertEquals(15, done.message().usage().totalTokens());
        assertTrue(mapper.terminalHandled());
    }

    @Test
    void mapsThinkingStream() throws Exception {
        var mapper = mapper();
        var all = new ArrayList<AssistantMessageEvent>();
        all.addAll(mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"content\":[],\"summary\":[],\"status\":\"in_progress\"}}")));
        all.addAll(mapper.onEvent("response.reasoning_text.delta", json(
                "{\"output_index\":0,\"delta\":\"think\"}")));
        all.addAll(mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"content\":[{\"type\":\"reasoning_text\",\"text\":\"think\"}],\"status\":\"completed\"}}")));
        all.addAll(mapper.onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}")));

        assertInstanceOf(AssistantMessageEvent.ThinkingStart.class, all.get(0));
        var delta = (AssistantMessageEvent.ThinkingDelta) all.get(1);
        assertEquals("think", delta.delta());
        assertEquals("think", ((Content.Thinking) delta.partial().content().get(0)).text());
        var end = (AssistantMessageEvent.ThinkingEnd) all.get(2);
        assertEquals("think", end.content());
        assertEquals(StopReason.STOP, ((AssistantMessageEvent.Done) all.get(3)).reason());
    }

    @Test
    void mapsToolCallAndEmitsExactlyOneToolCallEnd() throws Exception {
        var mapper = mapper();
        var all = new ArrayList<AssistantMessageEvent>();
        all.addAll(mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"\",\"status\":\"in_progress\"}}")));
        all.addAll(mapper.onEvent("response.function_call_arguments.delta", json(
                "{\"output_index\":0,\"delta\":\"{\\\"city\\\":\\\"Lo\"}")));
        all.addAll(mapper.onEvent("response.function_call_arguments.done", json(
                "{\"output_index\":0,\"arguments\":\"{\\\"city\\\":\\\"London\\\"}\"}")));
        all.addAll(mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"London\\\"}\",\"status\":\"completed\"}}")));
        all.addAll(mapper.onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}")));

        assertInstanceOf(AssistantMessageEvent.ToolCallStart.class, all.get(0));
        var delta = (AssistantMessageEvent.ToolCallDelta) all.get(1);
        assertEquals("{\"city\":\"Lo", delta.delta());
        // arguments.done backfills the suffix the deltas did not deliver,
        // without emitting ToolCallEnd.
        var backfill = (AssistantMessageEvent.ToolCallDelta) all.get(2);
        assertEquals("ndon\"}", backfill.delta());
        assertEquals(1, all.stream().filter(e -> e instanceof AssistantMessageEvent.ToolCallEnd).count());
        var end = (AssistantMessageEvent.ToolCallEnd) all.get(3);
        assertEquals("get_weather", end.toolCall().name());
        assertEquals("London", end.toolCall().arguments().get("city").asText());
        assertEquals("call_abc", OpenAiToolCallIds.callId(end.toolCall().id()));
        var done = (AssistantMessageEvent.Done) all.get(4);
        assertEquals(StopReason.TOOL_CALL, done.reason());
        assertTrue(mapper.terminalHandled());
    }

    @Test
    void argumentsDoneWithoutPriorDeltasBackfillsFullArgumentsAndEndsOnce() throws Exception {
        var mapper = mapper();
        var all = new ArrayList<AssistantMessageEvent>();
        all.addAll(mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"\",\"status\":\"in_progress\"}}")));
        all.addAll(mapper.onEvent("response.function_call_arguments.done", json(
                "{\"output_index\":0,\"arguments\":\"{\\\"city\\\":\\\"London\\\"}\"}")));
        all.addAll(mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"London\\\"}\",\"status\":\"completed\"}}")));
        all.addAll(mapper.onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}")));

        assertEquals(4, all.size());
        var backfill = (AssistantMessageEvent.ToolCallDelta) all.get(1);
        assertEquals("{\"city\":\"London\"}", backfill.delta());
        assertEquals(1, all.stream().filter(e -> e instanceof AssistantMessageEvent.ToolCallEnd).count());
        assertInstanceOf(AssistantMessageEvent.ToolCallEnd.class, all.get(2));
        assertInstanceOf(AssistantMessageEvent.Done.class, all.get(3));
    }

    @Test
    void maxOutputTokensIncompleteMapsToLength() throws Exception {
        var mapper = mapper();
        var events = mapper.onEvent("response.incomplete", json(
                "{\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}"));
        var done = (AssistantMessageEvent.Done) events.get(0);
        assertEquals(StopReason.LENGTH, done.reason());
        assertEquals(1, done.message().usage().input());
        assertEquals(1, done.message().usage().output());
        assertTrue(mapper.terminalHandled());
    }

    @Test
    void contentFilterIncompleteMapsToErrorAndPreservesPartialContentAndUsage() throws Exception {
        var mapper = mapper();
        mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":[],\"status\":\"in_progress\"}}"));
        mapper.onEvent("response.output_text.delta", json(
                "{\"output_index\":0,\"delta\":\"partial\"}"));
        var events = mapper.onEvent("response.incomplete", json(
                "{\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"},\"usage\":{\"input_tokens\":4,\"output_tokens\":2,\"total_tokens\":6}}}"));
        assertEquals(1, events.size());
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(0));
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("content_filter"));
        assertEquals("partial", ((Content.Text) error.error().content().get(0)).text());
        assertEquals(4, error.error().usage().input());
        assertEquals(2, error.error().usage().output());
        assertTrue(mapper.terminalHandled());
    }

    @Test
    void unknownIncompleteReasonMapsToError() throws Exception {
        var mapper = mapper();
        var events = mapper.onEvent("response.incomplete", json(
                "{\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"provider_limit\"},\"usage\":{\"input_tokens\":3,\"output_tokens\":1,\"total_tokens\":4}}}"));
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(0));
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("provider_limit"));
        assertEquals(3, error.error().usage().input());
        assertTrue(mapper.terminalHandled());
    }

    @Test
    void missingIncompleteReasonMapsToError() throws Exception {
        var mapper = mapper();
        var events = mapper.onEvent("response.incomplete", json(
                "{\"response\":{\"status\":\"incomplete\",\"usage\":{\"input_tokens\":2,\"output_tokens\":0,\"total_tokens\":2}}}"));
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(0));
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("did not report a reason"));
        assertEquals(2, error.error().usage().input());
        assertTrue(mapper.terminalHandled());
    }

    @Test
    void failedResponseMapsToError() throws Exception {
        var mapper = mapper();
        var events = mapper.onEvent("response.failed", json(
                "{\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"boom\"},\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}"));
        var error = (AssistantMessageEvent.Error) events.get(0);
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("server_error"));
        assertTrue(mapper.terminalHandled());
    }

    @Test
    void structuredContextOverflowIsClassifiedWithoutMessageGuessing() throws Exception {
        var failed = (AssistantMessageEvent.Error) mapper().onEvent("response.failed", json(
                "{\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"too large\"}}}"))
                .getFirst();
        assertEquals(ModelFailureKind.CONTEXT_OVERFLOW,
                failed.error().metadata().failureKind().orElseThrow());

        var proseOnly = (AssistantMessageEvent.Error) mapper().onEvent("error", json(
                "{\"code\":\"server_error\",\"message\":\"context is too long\"}"))
                .getFirst();
        assertTrue(proseOnly.error().metadata().failureKind().isEmpty());
    }

    @Test
    void errorEventMapsToError() throws Exception {
        var mapper = mapper();
        var events = mapper.onEvent("error", json("{\"code\":\"rate_limit\",\"message\":\"slow down\"}"));
        var error = (AssistantMessageEvent.Error) events.get(0);
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("rate_limit"));
        assertTrue(mapper.terminalHandled());
    }

    @Test
    void refusalDeltaAppendsToText() throws Exception {
        var mapper = mapper();
        var all = new ArrayList<AssistantMessageEvent>();
        all.addAll(mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"content\":[],\"status\":\"in_progress\"}}")));
        all.addAll(mapper.onEvent("response.refusal.delta", json(
                "{\"output_index\":0,\"delta\":\"no\"}")));
        var delta = (AssistantMessageEvent.TextDelta) all.get(1);
        assertEquals("no", delta.delta());
        assertEquals("no", ((Content.Text) delta.partial().content().get(0)).text());
    }

    @Test
    void unknownEventsAreIgnored() throws Exception {
        var mapper = mapper();
        assertTrue(mapper.onEvent("response.in_progress",
                json("{\"response\":{\"id\":\"resp_1\"}}")).isEmpty());
        assertFalse(mapper.terminalHandled());
    }

    @Test
    void deltaWithoutSlotIsIgnored() throws Exception {
        var mapper = mapper();
        assertTrue(mapper.onEvent("response.output_text.delta",
                json("{\"output_index\":7,\"delta\":\"x\"}")).isEmpty());
    }

    @Test
    void usageFallsBackToZeroWhenAbsent() throws Exception {
        var mapper = mapper();
        var events = mapper.onEvent("response.completed",
                json("{\"response\":{\"status\":\"completed\"}}"));
        var done = (AssistantMessageEvent.Done) events.get(0);
        assertEquals(0, done.message().usage().totalTokens());
        assertEquals(StopReason.STOP, done.reason());
        assertEquals(SOURCE, done.message().sourceModel());
    }

    @Test
    void terminalOutputBackfillsEncryptedReasoningWithoutOverwriting() throws Exception {
        var mapper = mapper();
        mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"content\":[],\"summary\":[],\"status\":\"in_progress\"}}"));
        mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"content\":[{\"type\":\"reasoning_text\",\"text\":\"think\"}],\"status\":\"completed\"}}"));
        var done = (AssistantMessageEvent.Done) mapper.onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_from_terminal\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}"))
                .get(0);
        var thinking = (Content.Thinking) done.message().content().get(0);
        var item = OpenAiReplayStateCodec.decodeReasoning(thinking.replayState(), "think").orElseThrow();
        assertEquals("enc_from_terminal", item.get("encrypted_content").asText());
        assertEquals(SOURCE, done.message().sourceModel());

        var alreadyEncrypted = mapper();
        alreadyEncrypted.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"content\":[],\"status\":\"in_progress\"}}"));
        alreadyEncrypted.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"content\":[{\"type\":\"reasoning_text\",\"text\":\"think\"}],\"encrypted_content\":\"enc_original\"}}"));
        var second = (AssistantMessageEvent.Done) alreadyEncrypted.onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_other\"}]}}"))
                .get(0);
        var kept = OpenAiReplayStateCodec.decodeReasoning(
                ((Content.Thinking) second.message().content().get(0)).replayState(), "think").orElseThrow();
        assertEquals("enc_original", kept.get("encrypted_content").asText());
    }

    @Test
    void messageDonePreservesItemIdAndPhase() throws Exception {
        var mapper = mapper();
        mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":[],\"status\":\"in_progress\"}}"));
        mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"phase\":\"final\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\"}],\"status\":\"completed\"}}"));
        var done = (AssistantMessageEvent.Done) mapper.onEvent("response.completed",
                json("{\"response\":{\"status\":\"completed\"}}")).get(0);
        var replay = OpenAiReplayStateCodec.decodeMessage(
                ((Content.Text) done.message().content().get(0)).replayState(), "Hello").orElseThrow();
        assertEquals("msg_1", replay.itemId());
        assertEquals("final", replay.phase());
    }

    @Test
    void reasoningSummaryPartsInsertBoundaryAndPreferSummary() throws Exception {
        var mapper = mapper();
        var all = new ArrayList<AssistantMessageEvent>();
        all.addAll(mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}")));
        all.addAll(mapper.onEvent("response.reasoning_summary_text.delta", json(
                "{\"output_index\":0,\"delta\":\"one\"}")));
        all.addAll(mapper.onEvent("response.reasoning_summary_part.done", json(
                "{\"output_index\":0}")));
        all.addAll(mapper.onEvent("response.reasoning_summary_text.delta", json(
                "{\"output_index\":0,\"delta\":\"two\"}")));
        all.addAll(mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"ONE\"},{\"type\":\"summary_text\",\"text\":\"TWO\"}],\"content\":[{\"type\":\"reasoning_text\",\"text\":\"ignored\"}]}}")));

        var boundary = (AssistantMessageEvent.ThinkingDelta) all.get(2);
        assertEquals("\n\n", boundary.delta());
        assertEquals("one\n\ntwo", ((Content.Thinking) all.get(3).partial().content().get(0)).text());
        var end = (AssistantMessageEvent.ThinkingEnd) all.get(4);
        assertEquals("ONE\n\nTWO", end.content());
    }

    @Test
    void thinkingFinalFallsBackFromContentToDeltas() throws Exception {
        var mapper = mapper();
        mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}"));
        mapper.onEvent("response.reasoning_text.delta", json(
                "{\"output_index\":0,\"delta\":\"delta-only\"}"));
        var withContent = mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"content\":[{\"type\":\"reasoning_text\",\"text\":\"from-content\"}]}}"));
        assertEquals("from-content", ((AssistantMessageEvent.ThinkingEnd) withContent.get(0)).content());

        var fallback = mapper();
        fallback.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_2\"}}"));
        fallback.onEvent("response.reasoning_text.delta", json(
                "{\"output_index\":0,\"delta\":\"delta-only\"}"));
        var end = fallback.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_2\"}}"));
        assertEquals("delta-only", ((AssistantMessageEvent.ThinkingEnd) end.get(0)).content());
    }

    @Test
    void refusalOnlyDoneKeepsRefusalTextWithoutDelta() throws Exception {
        var mapper = mapper();
        mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\"}}"));
        var end = mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"I cannot\"}]}}"));
        assertEquals("I cannot", ((AssistantMessageEvent.TextEnd) end.get(0)).content());
    }

    @Test
    void multipleMessageBlocksConcatenateWithoutExtraNewlines() throws Exception {
        var mapper = mapper();
        mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\"}}"));
        var end = mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\"},{\"type\":\"output_text\",\"text\":\"World\"}]}}"));
        assertEquals("HelloWorld", ((AssistantMessageEvent.TextEnd) end.get(0)).content());
    }

    @Test
    void doneOnlyMessageReasoningAndFunctionCallRecoverSlots() throws Exception {
        var mapper = mapper();
        var message = mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hi\"}]}}"));
        assertInstanceOf(AssistantMessageEvent.TextStart.class, message.get(0));
        assertEquals("Hi", ((AssistantMessageEvent.TextEnd) message.get(1)).content());

        var thinking = mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":1,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"why\"}]}}"));
        assertInstanceOf(AssistantMessageEvent.ThinkingStart.class, thinking.get(0));
        assertEquals("why", ((AssistantMessageEvent.ThinkingEnd) thinking.get(1)).content());

        var tool = mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":2,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"London\\\"}\"}}"));
        assertInstanceOf(AssistantMessageEvent.ToolCallStart.class, tool.get(0));
        assertEquals("London", ((AssistantMessageEvent.ToolCallEnd) tool.get(1)).toolCall().arguments().get("city").asText());
    }

    @Test
    void duplicateDoneDoesNotCreateAnotherBlock() throws Exception {
        var mapper = mapper();
        mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hi\"}]}}"));
        assertTrue(mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hi\"}]}}"))
                .isEmpty());
        assertEquals(1, mapper.content().size());
    }

    @Test
    void conflictingDoneTypeFails() throws Exception {
        var mapper = mapper();
        mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\"}}"));
        assertThrows(IllegalStateException.class, () -> mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"t\",\"arguments\":\"{}\"}}")));
    }

    @Test
    void initialArgumentsAreSeededBeforeDeltasAndFinalWins() throws Exception {
        var mapper = mapper();
        var all = new ArrayList<AssistantMessageEvent>();
        all.addAll(mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Lo\"}}")));
        var start = (AssistantMessageEvent.ToolCallStart) all.get(0);
        assertEquals("Lo", ((Content.ToolCall) start.partial().content().get(0)).arguments().get("city").asText());
        all.addAll(mapper.onEvent("response.function_call_arguments.delta", json(
                "{\"output_index\":0,\"delta\":\"ndon\\\"}\"}")));
        var delta = (AssistantMessageEvent.ToolCallDelta) all.get(1);
        assertEquals("London", ((Content.ToolCall) delta.partial().content().get(0)).arguments().get("city").asText());
        all.addAll(mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}")));
        var end = (AssistantMessageEvent.ToolCallEnd) all.get(2);
        assertEquals("Paris", end.toolCall().arguments().get("city").asText());
    }

    @Test
    void customToolStreamUsesDeclaredPropertyAndEscapesJsonDeltas() throws Exception {
        var mapper = new OpenAiEventMapper(SOURCE, Map.of("sample_tool", "payload"));
        var all = new ArrayList<AssistantMessageEvent>();
        all.addAll(mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\",\"input\":\"a\"}}")));
        all.addAll(mapper.onEvent("response.custom_tool_call_input.delta", json(
                "{\"output_index\":0,\"delta\":\"\\\"\\nb\\uD83D\\uDE00\"}")));
        all.addAll(mapper.onEvent("response.custom_tool_call_input.done", json(
                "{\"output_index\":0,\"input\":\"a\\\"\\nb\\uD83D\\uDE00\"}")));
        all.addAll(mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\",\"input\":\"a\\\"\\nb\\uD83D\\uDE00\"}}")));
        all.addAll(mapper.onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\",\"input\":\"a\\\"\\nb\\uD83D\\uDE00\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}")));

        var start = (AssistantMessageEvent.ToolCallStart) all.get(0);
        assertEquals("a", ((Content.ToolCall) start.partial().content().get(0)).arguments().get("payload").asText());
        var deltas = all.stream()
                .filter(e -> e instanceof AssistantMessageEvent.ToolCallDelta)
                .map(e -> ((AssistantMessageEvent.ToolCallDelta) e).delta())
                .toList();
        assertEquals(MAPPER.createObjectNode().put("payload", "a\"\nb😀"),
                MAPPER.readTree(String.join("", deltas)));
        var end = (AssistantMessageEvent.ToolCallEnd) all.get(all.size() - 2);
        assertEquals("a\"\nb😀", end.toolCall().arguments().get("payload").asText());
        assertTrue(end.toolCall().arguments().isObject());
        assertEquals(1, all.stream().filter(e -> e instanceof AssistantMessageEvent.ToolCallEnd).count());
        assertEquals(StopReason.TOOL_CALL, ((AssistantMessageEvent.Done) all.get(all.size() - 1)).reason());
    }

    @Test
    void customToolDoneOnlyAndTerminalRecoveryEmitStartDeltaEnd() throws Exception {
        var mapper = new OpenAiEventMapper(SOURCE, Map.of("sample_tool", "payload"));
        var doneOnly = mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\",\"input\":\"abc\"}}"));
        assertInstanceOf(AssistantMessageEvent.ToolCallStart.class, doneOnly.get(0));
        var delta = (AssistantMessageEvent.ToolCallDelta) doneOnly.get(1);
        assertEquals(MAPPER.createObjectNode().put("payload", "abc"), MAPPER.readTree(delta.delta()));
        assertEquals("abc", ((AssistantMessageEvent.ToolCallEnd) doneOnly.get(2)).toolCall().arguments().get("payload").asText());

        var terminal = new OpenAiEventMapper(SOURCE, Map.of("sample_tool", "payload"));
        var recovered = terminal.onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"custom_tool_call\",\"id\":\"ctc_9\",\"call_id\":\"call_9\",\"name\":\"sample_tool\",\"input\":\"only\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}"));
        assertInstanceOf(AssistantMessageEvent.ToolCallStart.class, recovered.get(0));
        assertEquals("only", ((AssistantMessageEvent.ToolCallEnd) recovered.get(2)).toolCall().arguments().get("payload").asText());
        assertInstanceOf(AssistantMessageEvent.Done.class, recovered.get(3));
    }

    @Test
    void customToolTerminalFinalizesOpenSlotWithoutItemDone() throws Exception {
        var mapper = new OpenAiEventMapper(SOURCE, Map.of("sample_tool", "payload"));
        var all = new ArrayList<AssistantMessageEvent>();
        all.addAll(mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\"}}")));
        all.addAll(mapper.onEvent("response.custom_tool_call_input.delta", json(
                "{\"output_index\":0,\"delta\":\"hel\"}")));
        all.addAll(mapper.onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\",\"input\":\"hello\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}")));

        long starts = all.stream().filter(e -> e instanceof AssistantMessageEvent.ToolCallStart).count();
        long ends = all.stream().filter(e -> e instanceof AssistantMessageEvent.ToolCallEnd).count();
        assertEquals(1, starts);
        assertEquals(1, ends);
        int endIndex = -1;
        int doneIndex = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) instanceof AssistantMessageEvent.ToolCallEnd) {
                endIndex = i;
            }
            if (all.get(i) instanceof AssistantMessageEvent.Done) {
                doneIndex = i;
            }
        }
        assertTrue(endIndex >= 0 && doneIndex > endIndex);
        var end = (AssistantMessageEvent.ToolCallEnd) all.get(endIndex);
        assertEquals("hello", end.toolCall().arguments().get("payload").asText());
        assertInstanceOf(AssistantMessageEvent.Done.class, all.get(all.size() - 1));
    }

    @Test
    void customToolRejectsNonTextualFieldsAndMissingDelta() throws Exception {
        var added = new OpenAiEventMapper(SOURCE, Map.of("sample_tool", "payload"));
        assertThrows(IllegalStateException.class, () -> added.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\",\"input\":1}}")));

        var open = new OpenAiEventMapper(SOURCE, Map.of("sample_tool", "payload"));
        var started = open.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\"}}"));
        assertInstanceOf(AssistantMessageEvent.ToolCallStart.class, started.get(0));
        assertEquals("", ((Content.ToolCall) ((AssistantMessageEvent.ToolCallStart) started.get(0))
                .partial().content().get(0)).arguments().get("payload").asText());

        assertThrows(IllegalStateException.class, () -> open.onEvent("response.custom_tool_call_input.delta", json(
                "{\"output_index\":0}")));
        assertThrows(IllegalStateException.class, () -> open.onEvent("response.custom_tool_call_input.delta", json(
                "{\"output_index\":0,\"delta\":1}")));
        assertThrows(IllegalStateException.class, () -> open.onEvent("response.custom_tool_call_input.delta", json(
                "{\"output_index\":0,\"delta\":null}")));
        assertThrows(IllegalStateException.class, () -> open.onEvent("response.custom_tool_call_input.done", json(
                "{\"output_index\":0,\"input\":[]}")));

        open.onEvent("response.custom_tool_call_input.delta", json(
                "{\"output_index\":0,\"delta\":\"ab\"}"));
        var doneMissingInput = open.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\"}}"));
        var end = (AssistantMessageEvent.ToolCallEnd) doneMissingInput.get(doneMissingInput.size() - 1);
        assertEquals("ab", end.toolCall().arguments().get("payload").asText());

        var nonTextualDone = new OpenAiEventMapper(SOURCE, Map.of("sample_tool", "payload"));
        nonTextualDone.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\"}}"));
        assertThrows(IllegalStateException.class, () -> nonTextualDone.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\",\"input\":{\"x\":1}}}")));
    }

    @Test
    void customToolRejectsNonMonotonicCloseChangeUnknownPropertyAndWrongSlot() throws Exception {
        var mapper = new OpenAiEventMapper(SOURCE, Map.of("sample_tool", "payload"));
        mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\",\"input\":\"ab\"}}"));
        mapper.onEvent("response.custom_tool_call_input.delta", json(
                "{\"output_index\":0,\"delta\":\"c\"}"));
        assertThrows(IllegalStateException.class, () -> mapper.onEvent("response.custom_tool_call_input.done", json(
                "{\"output_index\":0,\"input\":\"ac\"}")));

        var closed = new OpenAiEventMapper(SOURCE, Map.of("sample_tool", "payload"));
        closed.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\",\"input\":\"ab\"}}"));
        closed.onEvent("response.custom_tool_call_input.done", json(
                "{\"output_index\":0,\"input\":\"ab\"}"));
        assertTrue(closed.onEvent("response.custom_tool_call_input.done", json(
                "{\"output_index\":0,\"input\":\"ab\"}")).isEmpty());
        assertThrows(IllegalStateException.class, () -> closed.onEvent("response.custom_tool_call_input.done", json(
                "{\"output_index\":0,\"input\":\"abc\"}")));

        var unknown = new OpenAiEventMapper(SOURCE, Map.of());
        assertThrows(IllegalStateException.class, () -> unknown.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\",\"input\":\"x\"}}")));

        var function = mapper();
        function.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"\"}}"));
        assertThrows(IllegalStateException.class, () -> function.onEvent("response.custom_tool_call_input.delta", json(
                "{\"output_index\":0,\"delta\":\"x\"}")));
    }

    @Test
    void customToolDoesNotHardcodeInputProperty() throws Exception {
        var mapper = new OpenAiEventMapper(SOURCE, Map.of("sample_tool", "query"));
        var events = mapper.onEvent("response.output_item.done", json(
                "{\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ctc_1\",\"call_id\":\"call_1\",\"name\":\"sample_tool\",\"input\":\"hello\"}}"));
        var end = (AssistantMessageEvent.ToolCallEnd) events.get(events.size() - 1);
        assertTrue(end.toolCall().arguments().has("query"));
        assertFalse(end.toolCall().arguments().has("input"));
        assertEquals("hello", end.toolCall().arguments().get("query").asText());
    }

    @Test
    void completedResponseKeepsIdRequestIdReasonAndReasoningTokens() throws Exception {
        var mapper = mapper();
        mapper.acceptProviderRequestId(Optional.of("req_abc"));
        var done = (AssistantMessageEvent.Done) mapper.onEvent("response.completed", json(
                "{\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15,\"input_tokens_details\":{\"cached_tokens\":2},\"output_tokens_details\":{\"reasoning_tokens\":3}}}}"))
                .get(0);
        assertEquals("resp_1", done.message().metadata().responseId().orElseThrow());
        assertEquals("req_abc", done.message().metadata().providerRequestId().orElseThrow());
        assertEquals("completed", done.message().metadata().rawTerminalReason().orElseThrow());
        assertEquals(3, done.message().usage().reasoningTokens());
        assertEquals(8, done.message().usage().input());
        assertTrue(done.message().usage().cost().isEmpty());
        assertEquals(StopReason.STOP, done.reason());
    }

    @Test
    void partialEventsKeepEmptyMetadata() throws Exception {
        var mapper = mapper();
        var start = (AssistantMessageEvent.TextStart) mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"content\":[]}}"))
                .get(0);
        assertTrue(start.partial().metadata().isEmpty());
        assertTrue(start.partial().usage().cost().isEmpty());
        assertEquals(0, start.partial().usage().reasoningTokens());
    }

    @Test
    void incompleteAndFailedKeepMetadata() throws Exception {
        var incompleteMapper = mapper();
        incompleteMapper.acceptProviderRequestId(Optional.of("req_inc"));
        var incomplete = (AssistantMessageEvent.Error) incompleteMapper.onEvent("response.incomplete", json(
                "{\"response\":{\"id\":\"resp_inc\",\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"},\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}"))
                .get(0);
        assertEquals("resp_inc", incomplete.error().metadata().responseId().orElseThrow());
        assertEquals("incomplete.content_filter", incomplete.error().metadata().rawTerminalReason().orElseThrow());

        var failedMapper = mapper();
        var failed = (AssistantMessageEvent.Error) failedMapper.onEvent("response.failed", json(
                "{\"response\":{\"id\":\"resp_fail\",\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}"))
                .get(0);
        assertEquals("resp_fail", failed.error().metadata().responseId().orElseThrow());
        assertEquals("failed", failed.error().metadata().rawTerminalReason().orElseThrow());
    }

    @Test
    void sseErrorDoesNotInventResponseIdOrReason() throws Exception {
        var mapper = mapper();
        mapper.acceptProviderRequestId(Optional.of("req_only"));
        var error = (AssistantMessageEvent.Error) mapper.onEvent("error", json(
                "{\"id\":\"resp_fake\",\"status\":\"failed\",\"code\":\"rate_limit\",\"message\":\"slow down\"}"))
                .get(0);
        assertTrue(error.error().metadata().responseId().isEmpty());
        assertTrue(error.error().metadata().rawTerminalReason().isEmpty());
        assertEquals("req_only", error.error().metadata().providerRequestId().orElseThrow());
    }

    @Test
    void illegalUsageIsProtocolMappingError() throws Exception {
        var mapper = mapper();
        assertThrows(IllegalStateException.class, () -> mapper.onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":-1,\"output_tokens\":0,\"total_tokens\":0}}}")));
        assertThrows(IllegalStateException.class, () -> mapper().onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":2,\"output_tokens\":1,\"output_tokens_details\":{\"reasoning_tokens\":2}}}}")));
        assertThrows(IllegalStateException.class, () -> mapper().onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":2,\"output_tokens\":1,\"input_tokens_details\":{\"cached_tokens\":3}}}}")));
    }

    @Test
    void pricingAttachesCostAndUsesResponseServiceTier() throws Exception {
        var pricing = OpenAiPricing.of("USD", Map.of(SOURCE.modelId(), new OpenAiPricing.ModelPrice(
                new OpenAiPricing.TokenRates(
                        BigDecimal.ONE, new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ZERO),
                List.of(),
                Map.of(OpenAiServiceTier.FLEX, new BigDecimal("0.5")))));
        var mapper = new OpenAiEventMapper(
                SOURCE, Map.of(), Optional.of(pricing), Optional.of(OpenAiServiceTier.PRIORITY));
        var done = (AssistantMessageEvent.Done) mapper.onEvent("response.completed", json(
                "{\"response\":{\"id\":\"resp_cost\",\"status\":\"completed\",\"service_tier\":\"flex\",\"usage\":{\"input_tokens\":1000000,\"output_tokens\":1000000,\"total_tokens\":2000000}}}"))
                .get(0);
        var cost = done.message().usage().cost().orElseThrow();
        assertEquals(0, cost.input().compareTo(new BigDecimal("0.5")));
        assertEquals(0, cost.output().compareTo(BigDecimal.ONE));
        assertEquals(0, cost.total().compareTo(new BigDecimal("1.5")));
    }

    @Test
    void oversizeResponseIdIsProtocolMappingError() throws Exception {
        String id = "r".repeat(257);
        assertThrows(IllegalStateException.class, () -> mapper().onEvent("response.completed", json(
                "{\"response\":{\"id\":\"" + id + "\",\"status\":\"completed\"}}")));
    }

    @Test
    void illegalTerminalUsageKeepsConstructedCorrelationAndPartialEmpty() throws Exception {
        var mapper = mapper();
        mapper.acceptProviderRequestId(Optional.of("req_usage"));
        assertTrue(mapper.onEvent("response.created", json("{\"response\":{\"id\":\"resp_created\"}}")).isEmpty());
        assertEquals("resp_created", mapper.correlationSnapshot().responseId().orElseThrow());
        assertTrue(mapper.correlationSnapshot().rawTerminalReason().isEmpty());
        var start = (AssistantMessageEvent.TextStart) mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"content\":[]}}"))
                .get(0);
        assertTrue(start.partial().metadata().isEmpty());

        var thrown = assertThrows(IllegalStateException.class, () -> mapper.onEvent("response.completed", json(
                "{\"response\":{\"id\":\"resp_term\",\"status\":\"completed\",\"usage\":{\"input_tokens\":-1,\"output_tokens\":0}}}")));
        assertTrue(thrown.getMessage().contains("protocol mapping error"));
        var snap = mapper.correlationSnapshot();
        assertEquals("resp_term", snap.responseId().orElseThrow());
        assertEquals("req_usage", snap.providerRequestId().orElseThrow());
        assertEquals("completed", snap.rawTerminalReason().orElseThrow());
        assertTrue(start.partial().metadata().isEmpty());
    }

    @Test
    void createdIdFillsTerminalWhenResponseIdAbsentAndStaysOffPartial() throws Exception {
        var mapper = mapper();
        assertTrue(mapper.onEvent("response.created", json("{\"response\":{\"id\":\"resp_created\"}}")).isEmpty());
        var start = (AssistantMessageEvent.TextStart) mapper.onEvent("response.output_item.added", json(
                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"content\":[]}}"))
                .get(0);
        assertTrue(start.partial().metadata().isEmpty());
        var done = (AssistantMessageEvent.Done) mapper.onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}"))
                .get(0);
        assertEquals("resp_created", done.message().metadata().responseId().orElseThrow());
        assertEquals("completed", done.message().metadata().rawTerminalReason().orElseThrow());
        assertTrue(start.partial().metadata().isEmpty());
    }

    @Test
    void terminalResponseIdWinsOverCreatedId() throws Exception {
        var mapper = mapper();
        mapper.onEvent("response.created", json("{\"response\":{\"id\":\"resp_created\"}}"));
        var done = (AssistantMessageEvent.Done) mapper.onEvent("response.completed", json(
                "{\"response\":{\"id\":\"resp_terminal\",\"status\":\"completed\"}}"))
                .get(0);
        assertEquals("resp_terminal", done.message().metadata().responseId().orElseThrow());
    }

    @Test
    void createdIdRejectsNonStringAndOversize() throws Exception {
        assertThrows(IllegalStateException.class, () -> mapper().onEvent("response.created",
                json("{\"response\":{\"id\":1}}")));
        assertThrows(IllegalStateException.class, () -> mapper().onEvent("response.created",
                json("{\"response\":{\"id\":\"" + "r".repeat(257) + "\"}}")));
    }

    @Test
    void tokenFieldRejectsOutOfRangeFractionAndOverflow() throws Exception {
        var max = mapper().onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":9223372036854775807,\"output_tokens\":0,\"total_tokens\":9223372036854775807}}}"));
        assertEquals(Long.MAX_VALUE, ((AssistantMessageEvent.Done) max.get(0)).message().usage().input());

        assertThrows(IllegalStateException.class, () -> mapper().onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":9223372036854775808,\"output_tokens\":0}}}")));
        assertThrows(IllegalStateException.class, () -> mapper().onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1.5,\"output_tokens\":0}}}")));
        assertThrows(IllegalStateException.class, () -> mapper().onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":9223372036854775807,\"output_tokens\":1}}}")));
        assertThrows(IllegalStateException.class, () -> mapper().onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":9223372036854775807,\"output_tokens\":0,\"input_tokens_details\":{\"cached_tokens\":9223372036854775807,\"cache_write_tokens\":1}}}}")));
    }

    @Test
    void usageDetailsMustBeObjectWhenPresent() throws Exception {
        assertThrows(IllegalStateException.class, () -> mapper().onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"input_tokens_details\":[]}}}")));
        assertThrows(IllegalStateException.class, () -> mapper().onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"output_tokens_details\":1}}}")));
        var done = (AssistantMessageEvent.Done) mapper().onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"input_tokens_details\":null,\"output_tokens_details\":null}}}"))
                .get(0);
        assertEquals(1, done.message().usage().input());
        assertEquals(0, done.message().usage().reasoningTokens());
    }

    @Test
    void unknownServiceTierDoesNotInheritRequestedMultiplier() throws Exception {
        var pricing = OpenAiPricing.of("USD", Map.of(SOURCE.modelId(), new OpenAiPricing.ModelPrice(
                new OpenAiPricing.TokenRates(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                List.of(),
                Map.of(OpenAiServiceTier.PRIORITY, new BigDecimal("2")))));
        var requested = new OpenAiEventMapper(
                SOURCE, Map.of(), Optional.of(pricing), Optional.of(OpenAiServiceTier.PRIORITY));
        var unknown = (AssistantMessageEvent.Done) requested.onEvent("response.completed", json(
                "{\"response\":{\"status\":\"completed\",\"service_tier\":\"future_tier\",\"usage\":{\"input_tokens\":1000000,\"output_tokens\":0,\"total_tokens\":1000000}}}"))
                .get(0);
        assertEquals(0, unknown.message().usage().cost().orElseThrow().total().compareTo(BigDecimal.ONE));

        var absent = (AssistantMessageEvent.Done) new OpenAiEventMapper(
                SOURCE, Map.of(), Optional.of(pricing), Optional.of(OpenAiServiceTier.PRIORITY))
                .onEvent("response.completed", json(
                        "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1000000,\"output_tokens\":0,\"total_tokens\":1000000}}}"))
                .get(0);
        assertEquals(0, absent.message().usage().cost().orElseThrow().total().compareTo(new BigDecimal("2")));

        assertThrows(IllegalStateException.class, () -> new OpenAiEventMapper(
                SOURCE, Map.of(), Optional.of(pricing), Optional.of(OpenAiServiceTier.PRIORITY))
                .onEvent("response.completed", json(
                        "{\"response\":{\"status\":\"completed\",\"service_tier\":1,\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}")));
    }
}
