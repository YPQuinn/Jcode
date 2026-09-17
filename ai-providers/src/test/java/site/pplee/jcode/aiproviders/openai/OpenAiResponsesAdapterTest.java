package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.client.CacheRetention;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.client.PromptCacheOptions;
import site.pplee.jcode.ai.client.ToolChoice;
import site.pplee.jcode.ai.concurrent.CancellationRegistration;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.tool.GrammarSyntax;
import site.pplee.jcode.ai.tool.Requirement;
import site.pplee.jcode.ai.tool.ToolInputConstraint;
import site.pplee.jcode.ai.tool.ToolSpec;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.aiproviders.openai.support.FakeOpenAiServer;
import site.pplee.jcode.aiproviders.openai.support.MutableCancellationSignal;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end adapter tests against a local fake OpenAI endpoint: event
 * sequence, captured request shape, HTTP failure, malformed SSE, EOF/[DONE]
 * terminal rules, cancellation boundaries and stream-returns-first behavior.
 */
class OpenAiResponsesAdapterTest {
    private static final Model GPT = new Model("openai", "openai-responses", "gpt-4o-mini", "gpt-4o-mini");
    private static final String API_KEY = "sk-test-secret-123";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeOpenAiServer server;
    private OpenAiProvider provider;

    @BeforeEach
    void setUp() {
        server = new FakeOpenAiServer();
        var config = OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(GPT))
                .baseUrl(server.baseUrl())
                .build();
        provider = new OpenAiProvider(config, HttpClient.newHttpClient(), MAPPER);
    }

    @AfterEach
    void tearDown() {
        provider.close();
        server.close();
    }

    private static ModelRequest request() {
        return new ModelRequest(GPT.toRef(), "sys", List.of(), List.of());
    }

    private static String sseEvent(String name, String data) {
        return "event: " + name + "\ndata: " + data;
    }

    private static String sse(String... events) {
        var sb = new StringBuilder();
        for (String event : events) {
            sb.append(event).append("\n\n");
        }
        return sb.toString();
    }

    private static List<AssistantMessageEvent> drain(AssistantMessageStream stream) {
        var events = new ArrayList<AssistantMessageEvent>();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (true) {
                var event = stream.take();
                if (event == null) {
                    break;
                }
                events.add(event);
                if (event instanceof AssistantMessageEvent.Done || event instanceof AssistantMessageEvent.Error) {
                    break;
                }
            }
        });
        return events;
    }

    @Test
    void textResponseProducesEventSequenceAndCapturedRequest() throws Exception {
        server.respond(200, sse(
                sseEvent("response.output_item.added",
                        "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":[],\"status\":\"in_progress\"}}"),
                sseEvent("response.output_text.delta",
                        "{\"output_index\":0,\"delta\":\"Hello\"}"),
                sseEvent("response.output_item.done",
                        "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\"}],\"status\":\"completed\"}}"),
                sseEvent("response.completed",
                        "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15,\"input_tokens_details\":{\"cached_tokens\":2}}}}")));

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));

        assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
        assertInstanceOf(AssistantMessageEvent.TextStart.class, events.get(1));
        var delta = (AssistantMessageEvent.TextDelta) events.get(2);
        assertEquals("Hello", delta.delta());
        assertInstanceOf(AssistantMessageEvent.TextEnd.class, events.get(3));
        var done = (AssistantMessageEvent.Done) events.get(4);
        assertEquals(StopReason.STOP, done.reason());
        assertEquals(8, done.message().usage().input());
        assertEquals(2, done.message().usage().cacheRead());

        var captured = server.requests().get(0);
        assertEquals("/v1/responses", captured.path());
        assertEquals("Bearer " + API_KEY, captured.header("Authorization"));
        assertTrue(captured.header("Content-Type").contains("application/json"));
        var body = MAPPER.readTree(captured.body());
        assertEquals("gpt-4o-mini", body.get("model").asText());
        assertEquals("system", body.get("input").get(0).get("role").asText());
        assertEquals("sys", body.get("input").get(0).get("content").get(0).get("text").asText());
        assertTrue(body.get("stream").asBoolean());
        assertFalse(body.get("store").asBoolean());
    }

    @Test
    void sameModelReasoningAndToolResultReplayInOrder() throws Exception {
        server.respond(200, sse(
                sseEvent("response.output_item.added",
                        "{\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"content\":[],\"status\":\"in_progress\"}}"),
                sseEvent("response.reasoning_text.delta",
                        "{\"output_index\":0,\"delta\":\"think\"}"),
                sseEvent("response.output_item.done",
                        "{\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"content\":[{\"type\":\"reasoning_text\",\"text\":\"think\"}]}}"),
                sseEvent("response.output_item.added",
                        "{\"output_index\":1,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"\",\"status\":\"in_progress\"}}"),
                sseEvent("response.function_call_arguments.done",
                        "{\"output_index\":1,\"arguments\":\"{\\\"city\\\":\\\"London\\\"}\"}"),
                sseEvent("response.output_item.done",
                        "{\"output_index\":1,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"London\\\"}\"}}"),
                sseEvent("response.completed",
                        "{\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_abc\"},{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"London\\\"}\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}")));

        var first = drain(provider.stream(request(), new MutableCancellationSignal()));
        var done = assertInstanceOf(AssistantMessageEvent.Done.class, first.get(first.size() - 1));
        assertEquals(StopReason.TOOL_CALL, done.reason());
        assertEquals(GPT.toRef(), done.message().sourceModel());
        var toolCall = (Content.ToolCall) done.message().content().get(1);

        server.respond(200, sse(sseEvent("response.completed",
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}")));
        var followUp = new ModelRequest(GPT.toRef(), "sys", List.of(
                done.message(),
                new Message.ToolResultMessage(toolCall.id(), toolCall.name(),
                        List.of(new Content.Text("sunny")), false, Instant.parse("2026-01-01T00:00:00Z"))
        ), List.of());
        drain(provider.stream(followUp, new MutableCancellationSignal()));

        var body = MAPPER.readTree(server.requests().get(1).body());
        var input = body.get("input");
        assertEquals("system", input.get(0).get("role").asText());
        assertEquals("reasoning", input.get(1).get("type").asText());
        assertEquals("enc_abc", input.get(1).get("encrypted_content").asText());
        assertEquals("function_call", input.get(2).get("type").asText());
        assertEquals("fc_1", input.get(2).get("id").asText());
        assertEquals("function_call_output", input.get(3).get("type").asText());
        assertEquals("call_abc", input.get(3).get("call_id").asText());
        assertEquals("sunny", input.get(3).get("output").asText());
        assertEquals("reasoning.encrypted_content", body.get("include").get(0).asText());
    }

    @Test
    void functionCallResponseProducesToolCallAndToolCallReason() {
        server.respond(200, sse(
                sseEvent("response.output_item.added",
                        "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"\",\"status\":\"in_progress\"}}"),
                sseEvent("response.function_call_arguments.delta",
                        "{\"output_index\":0,\"delta\":\"{\\\"city\\\":\\\"London\\\"}\"}"),
                sseEvent("response.function_call_arguments.done",
                        "{\"output_index\":0,\"arguments\":\"{\\\"city\\\":\\\"London\\\"}\"}"),
                sseEvent("response.output_item.done",
                        "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_abc\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"London\\\"}\",\"status\":\"completed\"}}"),
                sseEvent("response.completed",
                        "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}")));

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));

        var start = (AssistantMessageEvent.ToolCallStart) events.get(1);
        assertEquals("get_weather", ((Content.ToolCall) start.partial().content().get(0)).name());
        var end = (AssistantMessageEvent.ToolCallEnd) events.get(3);
        assertEquals("London", end.toolCall().arguments().get("city").asText());
        assertEquals("call_abc", OpenAiToolCallIds.callId(end.toolCall().id()));
        var done = (AssistantMessageEvent.Done) events.get(4);
        assertEquals(StopReason.TOOL_CALL, done.reason());
    }

    @Test
    void non2xxProducesStartError() {
        server.respond(401, "{\"error\":{\"message\":\"bad key\"}}");

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
        var error = (AssistantMessageEvent.Error) events.get(1);
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("401"));
        assertTrue(error.error().errorMessage().contains("bad key"));
    }

    @Test
    void successAndNon2xxKeepAllowlistedMetadataOnly() {
        server.respond(200, sse(sseEvent("response.completed",
                "{\"response\":{\"id\":\"resp_ok\",\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}")),
                Map.of("X-Request-Id", "req_ok", "Set-Cookie", "session=secret", "Authorization", "Bearer leaked"));
        var done = (AssistantMessageEvent.Done) drain(provider.stream(request(), new MutableCancellationSignal())).get(1);
        assertEquals("resp_ok", done.message().metadata().responseId().orElseThrow());
        assertEquals("req_ok", done.message().metadata().providerRequestId().orElseThrow());
        assertEquals("completed", done.message().metadata().rawTerminalReason().orElseThrow());
        assertFalse(done.message().metadata().toString().contains("secret"));
        assertFalse(done.message().metadata().toString().contains("leaked"));
        assertFalse(done.message().toString().contains("session=secret"));

        server.respond(401, "{\"id\":\"resp_err\",\"error\":{\"message\":\"bad key\",\"request_id\":\"should-not-copy\"}}",
                Map.of("x-request-id", "req_err", "X-Arbitrary", "nope"));
        var error = (AssistantMessageEvent.Error) drain(provider.stream(request(), new MutableCancellationSignal())).get(1);
        assertTrue(error.error().metadata().responseId().isEmpty());
        assertTrue(error.error().metadata().rawTerminalReason().isEmpty());
        assertEquals("req_err", error.error().metadata().providerRequestId().orElseThrow());
        assertFalse(error.error().metadata().toString().contains("resp_err"));
        assertFalse(error.error().metadata().toString().contains("should-not-copy"));
        assertFalse(error.error().metadata().toString().contains("nope"));
        assertTrue(error.error().errorMessage().contains("bad key"));
    }

    @Test
    void pricingDoesNotChangeDefaultRequestPayload() throws Exception {
        var rates = new OpenAiPricing.TokenRates(
                java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
        try (var priced = new OpenAiProvider(OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(GPT))
                .baseUrl(server.baseUrl())
                .pricing(OpenAiPricing.of("USD", Map.of(GPT.modelId(), new OpenAiPricing.ModelPrice(rates))))
                .build(), HttpClient.newHttpClient(), MAPPER)) {
            server.respond(200, completedSse());
            drain(priced.stream(request(), new MutableCancellationSignal()));
        }
        var body = MAPPER.readTree(server.requests().get(0).body());
        assertFalse(body.has("pricing"));
        assertFalse(body.has("cost"));
        assertTrue(body.has("model"));
        assertTrue(body.has("input"));
    }

    @Test
    void malformedSseProducesStartError() {
        server.respond(200, "event: response.output_item.added\ndata: {not json\n\n");

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        var error = (AssistantMessageEvent.Error) events.get(1);
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("malformed"));
    }

    @Test
    void eofBeforeTerminalProducesStartError() {
        server.respond(200, "event: response.created\ndata: {\"response\":{\"id\":\"resp_1\"}}\n\n",
                Map.of("X-Request-Id", "req_eof"));

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        var error = (AssistantMessageEvent.Error) events.get(1);
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("before terminal"));
        assertEquals("resp_1", error.error().metadata().responseId().orElseThrow());
        assertEquals("req_eof", error.error().metadata().providerRequestId().orElseThrow());
        assertTrue(error.error().metadata().rawTerminalReason().isEmpty());
    }

    @Test
    void malformedAfterCreatedKeepsSafeCorrelation() {
        server.respond(200, sse(sseEvent("response.created",
                "{\"response\":{\"id\":\"resp_created\"}}"))
                + "event: response.output_item.added\ndata: {not json\n\n",
                Map.of("x-request-id", "req_created"));

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        var error = (AssistantMessageEvent.Error) events.get(1);
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("malformed"));
        assertEquals("resp_created", error.error().metadata().responseId().orElseThrow());
        assertEquals("req_created", error.error().metadata().providerRequestId().orElseThrow());
        assertTrue(error.error().metadata().rawTerminalReason().isEmpty());
    }

    @Test
    void doneMarkerBeforeTerminalProducesStartError() {
        server.respond(200, "data: [DONE]\n\n");

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        var error = (AssistantMessageEvent.Error) events.get(1);
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("before terminal"));
    }

    @Test
    void doneMarkerAfterTerminalIsIgnored() {
        server.respond(200, sse(
                sseEvent("response.completed",
                        "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}"),
                "data: [DONE]"));

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        assertInstanceOf(AssistantMessageEvent.Done.class, events.get(1));
        assertEquals(2, events.size());
    }

    @Test
    void dataOnlySseUsesJsonType() {
        server.respond(200, "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}\n\n");

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
        assertInstanceOf(AssistantMessageEvent.Done.class, events.get(1));
        assertEquals(StopReason.STOP, ((AssistantMessageEvent.Done) events.get(1)).reason());
    }

    @Test
    void namedEventWithMatchingJsonTypeStillWorks() {
        server.respond(200, sse(sseEvent("response.completed",
                "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}")));

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        assertInstanceOf(AssistantMessageEvent.Done.class, events.get(1));
    }

    @Test
    void conflictingSseNameAndJsonTypeProducesError() {
        server.respond(200, sse(sseEvent("response.completed",
                "{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\"}}")));

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(1));
        assertEquals(StopReason.ERROR, error.reason());
        assertEquals(OpenAiResponsesAdapter.SSE_EVENT_IDENTITY_CONFLICT, error.error().errorMessage());
        assertFalse(error.error().errorMessage().contains("response.completed"));
        assertFalse(error.error().errorMessage().contains("response.failed"));
    }

    @Test
    void contentFilterIncompleteProducesStartError() {
        server.respond(200, sse(sseEvent("response.incomplete",
                "{\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"},\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}")));

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        assertEquals(2, events.size());
        assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(1));
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("content_filter"));
        assertEquals(1, error.error().usage().input());
    }

    @Test
    void preRequestCancellationProducesAborted() {
        server.respond(200, sse(sseEvent("response.completed",
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}")));
        var cancellation = new MutableCancellationSignal();
        cancellation.cancel();

        var events = drain(provider.stream(request(), cancellation));
        assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
        var error = (AssistantMessageEvent.Error) events.get(1);
        assertEquals(StopReason.ABORTED, error.reason());
    }

    @Test
    void cancellationAfterMappingBeforeSendProducesAborted() {
        server.respond(200, sse(sseEvent("response.completed",
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}")));
        // The first isCancelled() check (pre-request) passes; the second
        // (post-mapping, pre-send) observes cancellation deterministically.
        var cancellation = new CancelOnSecondCheck();

        var events = drain(provider.stream(request(), cancellation));
        assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
        var error = (AssistantMessageEvent.Error) events.get(1);
        assertEquals(StopReason.ABORTED, error.reason());
        assertTrue(error.error().errorMessage().contains("mapping"));
    }

    @Test
    void unsafeToolCallIdFromProviderProducesStartError() {
        server.respond(200, sse(
                sseEvent("response.output_item.added",
                        "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc 1\",\"call_id\":\"bad id\",\"name\":\"get_weather\",\"arguments\":\"\",\"status\":\"in_progress\"}}")));

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        var error = (AssistantMessageEvent.Error) events.get(1);
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("event mapping failed"));
    }

    @Test
    void cancellationBetweenChunksProducesAborted() throws Exception {
        var gate = new CountDownLatch(1);
        server.setChunks(List.of(
                new FakeOpenAiServer.Chunk(sseEvent("response.output_item.added",
                        "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":[],\"status\":\"in_progress\"}}") + "\n\n", null),
                new FakeOpenAiServer.Chunk(sseEvent("response.completed",
                        "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}") + "\n\n", gate)));

        var cancellation = new MutableCancellationSignal();
        var stream = provider.stream(request(), cancellation);
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertInstanceOf(AssistantMessageEvent.TextStart.class, stream.take());

        cancellation.cancel();
        gate.countDown();

        var error = assertInstanceOf(AssistantMessageEvent.Error.class, stream.take());
        assertEquals(StopReason.ABORTED, error.reason());
    }

    @Test
    void cancelWhileWaitingHeadersProducesAborted() throws Exception {
        var hold = new CountDownLatch(1);
        server.holdBeforeHeaders(hold);
        server.respond(200, sse(sseEvent("response.completed",
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}")));

        var cancellation = new MutableCancellationSignal();
        var stream = provider.stream(request(), cancellation);
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertTrue(server.requestReceived().await(3, TimeUnit.SECONDS));

        cancellation.cancel();

        var error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertInstanceOf(AssistantMessageEvent.Error.class, stream.take()));
        assertEquals(StopReason.ABORTED, error.reason());
        assertEquals(1, hold.getCount(), "test must not pass by releasing the server header gate");
        assertTrue(stream.isDone());
    }

    @Test
    void cancelSilentBodyBeforeFirstEventProducesAborted() throws Exception {
        var hold = new CountDownLatch(1);
        server.setChunks(List.of(new FakeOpenAiServer.Chunk(sseEvent("response.completed",
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}") + "\n\n", hold)));

        var cancellation = new MutableCancellationSignal();
        var stream = provider.stream(request(), cancellation);
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertTrue(server.headersSent().await(3, TimeUnit.SECONDS));

        cancellation.cancel();

        var error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertInstanceOf(AssistantMessageEvent.Error.class, stream.take()));
        assertEquals(StopReason.ABORTED, error.reason());
        assertEquals(1, hold.getCount(), "test must not pass by releasing the silent-body gate");
        assertTrue(stream.isDone());
    }

    @Test
    void cancelAfterPartialTextPreservesPartialContent() throws Exception {
        var hold = new CountDownLatch(1);
        server.setChunks(List.of(
                new FakeOpenAiServer.Chunk(sse(
                        sseEvent("response.output_item.added",
                                "{\"output_index\":0,\"item\":{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":[],\"status\":\"in_progress\"}}"),
                        sseEvent("response.output_text.delta",
                                "{\"output_index\":0,\"delta\":\"Hello\"}")), null),
                new FakeOpenAiServer.Chunk(sseEvent("response.completed",
                        "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}") + "\n\n", hold)));

        var cancellation = new MutableCancellationSignal();
        var stream = provider.stream(request(), cancellation);
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertInstanceOf(AssistantMessageEvent.TextStart.class, stream.take());
        var delta = assertInstanceOf(AssistantMessageEvent.TextDelta.class, stream.take());
        assertEquals("Hello", delta.delta());

        cancellation.cancel();

        var error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertInstanceOf(AssistantMessageEvent.Error.class, stream.take()));
        assertEquals(StopReason.ABORTED, error.reason());
        assertEquals("Hello", ((Content.Text) error.error().content().get(0)).text());
        assertEquals(1, hold.getCount());
    }

    @Test
    void providerCloseAbortsSilentStreamOnceAndRejectsNewRequests() throws Exception {
        var hold = new CountDownLatch(1);
        server.setChunks(List.of(new FakeOpenAiServer.Chunk(sseEvent("response.completed",
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}") + "\n\n", hold)));

        var stream = provider.stream(request(), new MutableCancellationSignal());
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertTrue(server.headersSent().await(3, TimeUnit.SECONDS));

        provider.close();

        var error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertInstanceOf(AssistantMessageEvent.Error.class, stream.take()));
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("provider is closed"));
        assertTrue(stream.isDone());

        var closed = drain(provider.stream(request(), new MutableCancellationSignal()));
        assertEquals(2, closed.size());
        assertInstanceOf(AssistantMessageEvent.Start.class, closed.get(0));
        var closedError = assertInstanceOf(AssistantMessageEvent.Error.class, closed.get(1));
        assertEquals(StopReason.ERROR, closedError.reason());
        assertTrue(closedError.error().errorMessage().contains("provider is closed"));
    }

    @Test
    void disallowedToolResultContentProducesStartError() {
        var toolCallId = OpenAiToolCallIds.encode("call_abc", "fc_1");
        var request = new ModelRequest(GPT.toRef(), "sys", List.of(
                new Message.Assistant(List.of(
                        new Content.ToolCall(toolCallId, "look", MAPPER.createObjectNode())
                ), StopReason.TOOL_CALL, null, Usage.zero(), Instant.parse("2026-01-01T00:00:00Z")),
                new Message.ToolResultMessage(toolCallId, "look",
                        List.of(new Content.Thinking("hidden")), false,
                        Instant.parse("2026-01-01T00:00:00Z"))
        ), List.of());

        var events = drain(provider.stream(request, new MutableCancellationSignal()));
        assertEquals(2, events.size());
        assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(1));
        assertEquals(StopReason.ERROR, error.reason());
        assertEquals("request mapping failed", error.error().errorMessage());
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void requiredGrammarOnConservativeEndpointIsStartThenError() throws Exception {
        var conservative = new OpenAiProvider(OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(GPT))
                .baseUrl(server.baseUrl())
                .compatibility(new OpenAiResponsesCompatibility(true))
                .build(), HttpClient.newHttpClient(), MAPPER);
        try {
            var schema = MAPPER.readTree(
                    "{\"type\":\"object\",\"properties\":{\"payload\":{\"type\":\"string\"}},\"required\":[\"payload\"]}");
            var spec = new ToolSpec("sample_tool", "g", schema,
                    new ToolInputConstraint.Grammar(Map.of(GrammarSyntax.LARK, "start: /x/"), Requirement.REQUIRE));
            var request = new ModelRequest(GPT.toRef(), "sys", List.of(), List.of(spec));
            var events = drain(conservative.stream(request, new MutableCancellationSignal()));
            assertEquals(2, events.size());
            assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
            var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(1));
            assertEquals(StopReason.ERROR, error.reason());
            assertEquals("request mapping failed", error.error().errorMessage());
            assertTrue(server.requests().isEmpty());
        } finally {
            conservative.close();
        }
    }

    @Test
    void developerRoleAndVisionImagesReachCapturedRequest() throws Exception {
        var visionProvider = new OpenAiProvider(OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(GPT))
                .baseUrl(server.baseUrl())
                .capabilities(Map.of(GPT.modelId(),
                        new OpenAiModelCapabilities(false, Map.of(), true, true)))
                .build(), HttpClient.newHttpClient(), MAPPER);
        try {
            server.respond(200, sse(sseEvent("response.completed",
                    "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}")));
            var image = new Content.Image("image/png", "AA==");
            var request = new ModelRequest(GPT.toRef(), "sys",
                    List.of(new Message.User(List.of(new Content.Text("see"), image),
                            Instant.parse("2026-01-01T00:00:00Z"))),
                    List.of());
            var events = drain(visionProvider.stream(request, new MutableCancellationSignal()));
            assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
            assertInstanceOf(AssistantMessageEvent.Done.class, events.get(1));

            var body = MAPPER.readTree(server.requests().get(0).body());
            assertEquals("developer", body.get("input").get(0).get("role").asText());
            assertEquals("input_image", body.get("input").get(1).get("content").get(1).get("type").asText());
            assertEquals("data:image/png;base64,AA==",
                    body.get("input").get(1).get("content").get(1).get("image_url").asText());
        } finally {
            visionProvider.close();
        }
    }

    @Test
    void forcedSystemCompatibilityOverridesDeveloperPreference() throws Exception {
        var forced = new OpenAiProvider(OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(GPT))
                .baseUrl(server.baseUrl())
                .capabilities(Map.of(GPT.modelId(),
                        new OpenAiModelCapabilities(false, Map.of(), false, true)))
                .compatibility(OpenAiResponsesCompatibility.forceSystem())
                .build(), HttpClient.newHttpClient(), MAPPER);
        try {
            server.respond(200, sse(sseEvent("response.completed",
                    "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}")));
            drain(forced.stream(request(), new MutableCancellationSignal()));
            var body = MAPPER.readTree(server.requests().get(0).body());
            assertEquals("system", body.get("input").get(0).get("role").asText());
        } finally {
            forced.close();
        }
    }

    @Test
    void endpointProfilesSendExpectedSessionHeaders() throws Exception {
        server.respond(200, completedSse());
        var options = ModelRequestOptions.defaults().withPromptCache(
                PromptCacheOptions.defaults()
                        .withRetention(CacheRetention.SHORT)
                        .withCacheKey("cache-key")
                        .withSessionAffinityId("session-1"));
        var request = new ModelRequest(GPT.toRef(), "sys", List.of(), List.of(),
                ThinkingLevel.PROVIDER_DEFAULT, options);

        drain(provider.stream(request, new MutableCancellationSignal()));
        var openai = server.requests().get(0);
        assertEquals("session-1", openai.header("session_id"));
        assertEquals("session-1", openai.header("x-client-request-id"));
        assertEquals(null, openai.header("x-session-id"));
        assertEquals("cache-key", MAPPER.readTree(openai.body()).get("prompt_cache_key").asText());

        try (var noSession = providerWith(OpenAiResponsesCompatibility.openaiNoSession(), OpenAiHeaders.empty(), null);
             var openRouter = providerWith(OpenAiResponsesCompatibility.openRouter(), OpenAiHeaders.empty(), null)) {
            server.respond(200, completedSse());
            drain(noSession.stream(request, new MutableCancellationSignal()));
            var capturedNoSession = server.requests().get(1);
            assertEquals(null, capturedNoSession.header("session_id"));
            assertEquals("session-1", capturedNoSession.header("x-client-request-id"));
            assertEquals(null, capturedNoSession.header("x-session-id"));

            server.respond(200, completedSse());
            drain(openRouter.stream(request, new MutableCancellationSignal()));
            var capturedRouter = server.requests().get(2);
            assertEquals(null, capturedRouter.header("session_id"));
            assertEquals(null, capturedRouter.header("x-client-request-id"));
            assertEquals("session-1", capturedRouter.header("x-session-id"));
        }
    }

    @Test
    void illegalSessionHeaderValueIsRedactedTerminalError() {
        String secret = "leaked-session-secret-xyz";
        var request = new ModelRequest(GPT.toRef(), "sys", List.of(), List.of(),
                ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withPromptCache(
                        PromptCacheOptions.defaults().withSessionAffinityId(secret + "\n")));

        var events = drain(provider.stream(request, new MutableCancellationSignal()));
        assertEquals(2, events.size());
        assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(1));
        assertEquals(StopReason.ERROR, error.reason());
        assertEquals("request headers could not be applied", error.error().errorMessage());
        assertFalse(error.error().errorMessage().contains(secret));
        assertFalse(error.toString().contains(secret));
        assertFalse(error.error().toString().contains(secret));
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void cacheRetentionNoneOmitsAffinityAndCacheFields() throws Exception {
        server.respond(200, completedSse());
        var request = new ModelRequest(GPT.toRef(), "sys", List.of(), List.of(),
                ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withPromptCache(
                        PromptCacheOptions.none()
                                .withCacheKey("cache-key")
                                .withSessionAffinityId("session-1")));
        drain(provider.stream(request, new MutableCancellationSignal()));
        var captured = server.requests().get(0);
        assertEquals(null, captured.header("session_id"));
        assertEquals(null, captured.header("x-client-request-id"));
        assertEquals(null, captured.header("x-session-id"));
        var body = MAPPER.readTree(captured.body());
        assertFalse(body.has("prompt_cache_key"));
        assertFalse(body.has("prompt_cache_retention"));
    }

    @Test
    void safeCustomHeaderIsSentAndUnsupportedToolChoiceIsTerminalMappingError() throws Exception {
        var headers = OpenAiHeaders.builder().header("X-Trace", "trace-1").build();
        try (var custom = providerWith(OpenAiResponsesCompatibility.openai(), headers, OpenAiServiceTier.FLEX)) {
            server.respond(200, completedSse());
            drain(custom.stream(request(), new MutableCancellationSignal()));
            var captured = server.requests().get(0);
            assertEquals("trace-1", captured.header("X-Trace"));
            assertEquals("flex", MAPPER.readTree(captured.body()).get("service_tier").asText());
        }

        var bad = new ModelRequest(GPT.toRef(), "sys", List.of(), List.of(ToolSpec.minimal("echo")),
                ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withToolChoice(ToolChoice.specific("missing")));
        var events = drain(provider.stream(bad, new MutableCancellationSignal()));
        assertEquals(2, events.size());
        assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(1));
        assertEquals(StopReason.ERROR, error.reason());
        assertEquals("request mapping failed", error.error().errorMessage());
        assertEquals(1, server.requests().size());
    }

    @Test
    void malformedToolNameIsStartThenErrorWithoutLeakingValue() {
        String dirty = "echo\uD83D";
        var request = new ModelRequest(GPT.toRef(), "sys", List.of(), List.of(ToolSpec.minimal(dirty)));
        var events = drain(provider.stream(request, new MutableCancellationSignal()));
        assertEquals(2, events.size());
        assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(1));
        assertEquals(StopReason.ERROR, error.reason());
        assertEquals("request mapping failed", error.error().errorMessage());
        assertFalse(error.error().errorMessage().contains(dirty));
        assertFalse(error.toString().contains(dirty));
        assertTrue(server.requests().isEmpty());
        assertTrue(streamIsTerminalOnce(events));
    }

    @Test
    void malformedSessionIdIsHeaderFailureWithoutLeakingValue() {
        String secret = "session\uD83Dsecret";
        var request = new ModelRequest(GPT.toRef(), "sys", List.of(), List.of(),
                ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withPromptCache(
                        PromptCacheOptions.defaults().withSessionAffinityId(secret)));
        var events = drain(provider.stream(request, new MutableCancellationSignal()));
        assertEquals(2, events.size());
        assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(1));
        assertEquals("request headers could not be applied", error.error().errorMessage());
        assertFalse(error.error().errorMessage().contains(secret));
        assertFalse(error.toString().contains(secret));
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void malformedApiKeyIsHeaderFailureWithoutLeakingSecret() {
        String secret = "sk-secret-value\uD83D-xyz";
        var dirty = new OpenAiProvider(OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(secret))
                .models(List.of(GPT))
                .baseUrl(server.baseUrl())
                .build(), HttpClient.newHttpClient(), MAPPER);
        try {
            var events = drain(dirty.stream(request(), new MutableCancellationSignal()));
            assertEquals(2, events.size());
            assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
            var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(1));
            assertEquals(StopReason.ERROR, error.reason());
            assertEquals("request headers could not be applied", error.error().errorMessage());
            assertFalse(error.error().errorMessage().contains(secret));
            assertFalse(error.error().errorMessage().contains("sk-secret-value"));
            assertFalse(error.toString().contains(secret));
            assertFalse(error.error().toString().contains(secret));
            assertFalse(dirty.toString().contains(secret));
            assertTrue(server.requests().isEmpty());
            assertTrue(streamIsTerminalOnce(events));
        } finally {
            dirty.close();
        }
    }

    @Test
    void malformedPromptCacheKeyIsStartThenErrorWithoutLeakingValue() {
        String dirty = "cache-key\uD83D";
        var request = new ModelRequest(GPT.toRef(), "sys", List.of(), List.of(),
                ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withPromptCache(
                        PromptCacheOptions.defaults()
                                .withRetention(CacheRetention.SHORT)
                                .withCacheKey(dirty)));
        var events = drain(provider.stream(request, new MutableCancellationSignal()));
        assertEquals(2, events.size());
        assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(1));
        assertEquals("request mapping failed", error.error().errorMessage());
        assertFalse(error.error().errorMessage().contains(dirty));
        assertFalse(error.toString().contains(dirty));
        assertEquals(dirty, request.options().promptCache().cacheKey());
        assertTrue(server.requests().isEmpty());
        assertTrue(streamIsTerminalOnce(events));
    }

    @Test
    void sanitizedPromptStillSendsOnceAndDoesNotLeakSecrets() throws Exception {
        server.respond(200, completedSse());
        var request = new ModelRequest(GPT.toRef(), "sys \uD83Dhello", List.of(), List.of());
        var events = drain(provider.stream(request, new MutableCancellationSignal()));
        assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
        assertInstanceOf(AssistantMessageEvent.Done.class, events.get(1));
        assertEquals(1, server.requests().size());
        var body = MAPPER.readTree(server.requests().get(0).body());
        assertEquals("sys hello", body.get("input").get(0).get("content").get(0).get("text").asText());
        assertFalse(events.get(1).toString().contains(API_KEY));
    }

    @Test
    void streamReturnsBeforeServerSendsEvents() {
        var gate = new CountDownLatch(1);
        server.setChunks(List.of(new FakeOpenAiServer.Chunk(sseEvent("response.completed",
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}") + "\n\n", gate)));

        var stream = provider.stream(request(), new MutableCancellationSignal());
        // Start must be available even though the server has not sent anything yet.
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertInstanceOf(AssistantMessageEvent.Start.class, stream.take()));

        gate.countDown();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertInstanceOf(AssistantMessageEvent.Done.class, stream.take()));
    }

    private OpenAiProvider providerWith(
            OpenAiResponsesCompatibility compatibility,
            OpenAiHeaders headers,
            OpenAiServiceTier serviceTier
    ) {
        var builder = OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(GPT))
                .baseUrl(server.baseUrl())
                .compatibility(compatibility)
                .headers(headers);
        if (serviceTier != null) {
            builder.serviceTier(serviceTier);
        }
        return new OpenAiProvider(builder.build(), HttpClient.newHttpClient(), MAPPER);
    }

    private static boolean streamIsTerminalOnce(List<AssistantMessageEvent> events) {
        long terminals = events.stream()
                .filter(event -> event instanceof AssistantMessageEvent.Done
                        || event instanceof AssistantMessageEvent.Error)
                .count();
        return terminals == 1;
    }

    private static String completedSse() {
        return sse(sseEvent("response.completed",
                "{\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}"));
    }

    /** Cancellation signal that reports cancelled from the second check onward. */
    private static final class CancelOnSecondCheck implements CancellationSignal {
        private int checks;

        @Override
        public boolean isCancelled() {
            return ++checks >= 2;
        }

        @Override
        public void throwIfCancelled() {
        }

        @Override
        public CancellationRegistration onCancellation(Runnable listener) {
            return () -> { };
        }
    }
}
