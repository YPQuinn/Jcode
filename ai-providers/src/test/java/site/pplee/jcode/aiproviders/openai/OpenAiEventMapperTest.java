package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Responses event → Jcode event mapping tests: text/thinking/tool-call
 * start-delta-end ordering, partial accumulation, usage formula, stop-reason
 * mapping and terminal handling.
 */
class OpenAiEventMapperTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void mapsTextStreamWithAccumulatedPartialsAndUsage() throws Exception {
        var mapper = new OpenAiEventMapper();
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
        var mapper = new OpenAiEventMapper();
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
        var mapper = new OpenAiEventMapper();
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
        var mapper = new OpenAiEventMapper();
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
    void incompleteResponseMapsToLength() throws Exception {
        var mapper = new OpenAiEventMapper();
        var events = mapper.onEvent("response.incomplete", json(
                "{\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}"));
        var done = (AssistantMessageEvent.Done) events.get(0);
        assertEquals(StopReason.LENGTH, done.reason());
        assertTrue(mapper.terminalHandled());
    }

    @Test
    void failedResponseMapsToError() throws Exception {
        var mapper = new OpenAiEventMapper();
        var events = mapper.onEvent("response.failed", json(
                "{\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"boom\"},\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}"));
        var error = (AssistantMessageEvent.Error) events.get(0);
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("server_error"));
        assertTrue(mapper.terminalHandled());
    }

    @Test
    void errorEventMapsToError() throws Exception {
        var mapper = new OpenAiEventMapper();
        var events = mapper.onEvent("error", json("{\"code\":\"rate_limit\",\"message\":\"slow down\"}"));
        var error = (AssistantMessageEvent.Error) events.get(0);
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("rate_limit"));
        assertTrue(mapper.terminalHandled());
    }

    @Test
    void refusalDeltaAppendsToText() throws Exception {
        var mapper = new OpenAiEventMapper();
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
        var mapper = new OpenAiEventMapper();
        assertTrue(mapper.onEvent("response.created",
                json("{\"response\":{\"id\":\"resp_1\"}}")).isEmpty());
        assertFalse(mapper.terminalHandled());
    }

    @Test
    void deltaWithoutSlotIsIgnored() throws Exception {
        var mapper = new OpenAiEventMapper();
        assertTrue(mapper.onEvent("response.output_text.delta",
                json("{\"output_index\":7,\"delta\":\"x\"}")).isEmpty());
    }

    @Test
    void usageFallsBackToZeroWhenAbsent() throws Exception {
        var mapper = new OpenAiEventMapper();
        var events = mapper.onEvent("response.completed",
                json("{\"response\":{\"status\":\"completed\"}}"));
        var done = (AssistantMessageEvent.Done) events.get(0);
        assertEquals(0, done.message().usage().totalTokens());
        assertEquals(StopReason.STOP, done.reason());
    }
}
