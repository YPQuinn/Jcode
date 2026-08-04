package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.aiproviders.openai.support.FakeOpenAiServer;
import site.pplee.jcode.aiproviders.openai.support.MutableCancellationSignal;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

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
    void malformedSseProducesStartError() {
        server.respond(200, "event: response.output_item.added\ndata: {not json\n\n");

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        var error = (AssistantMessageEvent.Error) events.get(1);
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("malformed"));
    }

    @Test
    void eofBeforeTerminalProducesStartError() {
        server.respond(200, "event: response.created\ndata: {\"response\":{\"id\":\"resp_1\"}}\n\n");

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        var error = (AssistantMessageEvent.Error) events.get(1);
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("before terminal"));
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
    }
}
