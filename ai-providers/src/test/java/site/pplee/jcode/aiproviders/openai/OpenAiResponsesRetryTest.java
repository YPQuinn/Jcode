package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.client.PromptCacheOptions;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.aiproviders.openai.support.CloseTrackingInputStream;
import site.pplee.jcode.aiproviders.openai.support.FakeOpenAiServer;
import site.pplee.jcode.aiproviders.openai.support.MutableCancellationSignal;
import site.pplee.jcode.aiproviders.openai.support.ScriptedHttpClient;
import site.pplee.jcode.aiproviders.openai.support.ThrowingSendHttpClient;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fake-server coverage for OAI-016: default single attempt, retryable
 * statuses and headers, delay sources, cancel/close during backoff,
 * no retry after 2xx SSE accept, stable bodies, terminal-once, and
 * redacted diagnostics.
 */
class OpenAiResponsesRetryTest {
    private static final Model GPT = new Model("openai", "openai-responses", "gpt-4o-mini", "gpt-4o-mini");
    private static final String API_KEY = "sk-test-secret-123";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeOpenAiServer server;
    private OpenAiProvider provider;

    @BeforeEach
    void setUp() {
        server = new FakeOpenAiServer();
        provider = providerWith(OpenAiRetryPolicy.disabled());
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.close();
        }
        server.close();
    }

    @Test
    void defaultPolicySendsASingleAttempt() {
        server.enqueue(500, "{\"error\":{\"message\":\"busy\"}}", Map.of("retry-after-ms", "0"));
        server.respond(200, completedSse());

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        assertStartThen(events, AssistantMessageEvent.Error.class);
        assertEquals(1, server.requests().size());
        assertTrue(((AssistantMessageEvent.Error) events.get(1)).error().errorMessage().contains("HTTP 500"));
    }

    @Test
    void transportFailureThenSuccess() {
        reopen(OpenAiRetryPolicy.of(1));
        server.enqueueTransportFailure();
        server.respond(200, completedSse());

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        assertStartThen(events, AssistantMessageEvent.Done.class);
        assertEquals(2, server.requests().size());
        assertEquals(server.requests().get(0).body(), server.requests().get(1).body());
    }

    @Test
    void retryableStatusesThenSuccess() {
        reopen(OpenAiRetryPolicy.of(1));
        for (int status : List.of(408, 409, 429, 500)) {
            int before = server.requests().size();
            server.enqueue(status, "{\"error\":{\"message\":\"retry me\"}}", Map.of("retry-after-ms", "0"));
            server.respond(200, completedSse());
            var events = drain(provider.stream(request(), new MutableCancellationSignal()));
            assertStartThen(events, AssistantMessageEvent.Done.class);
            assertEquals(before + 2, server.requests().size(), "status " + status);
        }
    }

    @Test
    void status499And600AreNotRetriedWhile500And599Are() {
        reopen(OpenAiRetryPolicy.of(1));
        server.enqueue(499, "{\"error\":{\"message\":\"not 5xx\"}}", Map.of("retry-after-ms", "0"));
        drain(provider.stream(request(), new MutableCancellationSignal()));
        assertEquals(1, server.requests().size());

        server.enqueue(600, "{\"error\":{\"message\":\"not http 5xx\"}}", Map.of("retry-after-ms", "0"));
        drain(provider.stream(request(), new MutableCancellationSignal()));
        assertEquals(2, server.requests().size());

        server.enqueue(599, "{\"error\":{\"message\":\"last 5xx\"}}", Map.of("retry-after-ms", "0"));
        server.respond(200, completedSse());
        assertStartThen(drain(provider.stream(request(), new MutableCancellationSignal())),
                AssistantMessageEvent.Done.class);
        assertEquals(4, server.requests().size());
    }

    @Test
    void clientErrorsAreNotRetried() {
        reopen(OpenAiRetryPolicy.of(2));
        for (int status : List.of(400, 401, 403)) {
            server.enqueue(status, "{\"error\":{\"message\":\"nope\"}}", Map.of("retry-after-ms", "0"));
            var events = drain(provider.stream(request(), new MutableCancellationSignal()));
            assertStartThen(events, AssistantMessageEvent.Error.class);
        }
        assertEquals(3, server.requests().size());
    }

    @Test
    void shouldRetryHeaderOverridesStatusRules() {
        reopen(OpenAiRetryPolicy.of(1));
        server.enqueue(400, "{\"error\":{\"message\":\"force retry\"}}",
                Map.of("x-should-retry", "true", "retry-after-ms", "0"));
        server.respond(200, completedSse());
        assertStartThen(drain(provider.stream(request(), new MutableCancellationSignal())),
                AssistantMessageEvent.Done.class);
        assertEquals(2, server.requests().size());

        server.enqueue(429, "{\"error\":{\"message\":\"do not retry\"}}",
                Map.of("x-should-retry", "false", "retry-after-ms", "0"));
        var blocked = drain(provider.stream(request(), new MutableCancellationSignal()));
        assertStartThen(blocked, AssistantMessageEvent.Error.class);
        assertEquals(3, server.requests().size());
    }

    @Test
    void retryAfterMsSecondsAndHttpDate() {
        reopen(OpenAiRetryPolicy.of(1));

        server.enqueue(429, "{\"error\":{\"message\":\"ms\"}}", Map.of("retry-after-ms", "0"));
        server.respond(200, completedSse());
        assertStartThen(drain(provider.stream(request(), new MutableCancellationSignal())),
                AssistantMessageEvent.Done.class);

        server.enqueue(429, "{\"error\":{\"message\":\"sec\"}}", Map.of("Retry-After", "0"));
        server.respond(200, completedSse());
        assertStartThen(drain(provider.stream(request(), new MutableCancellationSignal())),
                AssistantMessageEvent.Done.class);

        server.enqueue(429, "{\"error\":{\"message\":\"date\"}}",
                Map.of("Retry-After", "Wed, 01 Jan 2020 00:00:00 GMT"));
        server.respond(200, completedSse());
        assertStartThen(drain(provider.stream(request(), new MutableCancellationSignal())),
                AssistantMessageEvent.Done.class);
        assertEquals(6, server.requests().size());
    }

    @Test
    void serverDelayAboveMaximumIsFinalFailure() {
        reopen(OpenAiRetryPolicy.builder()
                .maxRetries(2)
                .maxServerDelay(Duration.ofSeconds(1))
                .build());
        server.enqueue(429, "{\"error\":{\"message\":\"later\"}}", Map.of("retry-after-ms", "5000"));
        server.respond(200, completedSse());

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(1));
        assertTrue(error.error().errorMessage().contains("server requested retry delay exceeds maximum"));
        assertTrue(error.error().errorMessage().contains("HTTP 429"));
        assertEquals(1, server.requests().size());
    }

    @Test
    void callerCancellationDuringBackoffIsAborted() throws Exception {
        reopen(OpenAiRetryPolicy.of(2));
        server.enqueue(429, "{\"error\":{\"message\":\"wait\"}}", Map.of("retry-after-ms", "60000"));
        server.respond(200, completedSse());

        var cancellation = new MutableCancellationSignal();
        var stream = provider.stream(request(), cancellation);
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertTrue(server.awaitRequests(1, Duration.ofSeconds(3)));
        cancellation.cancel();

        var error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertInstanceOf(AssistantMessageEvent.Error.class, stream.take()));
        assertEquals(StopReason.ABORTED, error.reason());
        assertEquals(1, server.requests().size());
        assertTrue(stream.isDone());
    }

    @Test
    void providerCloseDuringBackoffIsErrorAndRejectsRetry() throws Exception {
        reopen(OpenAiRetryPolicy.of(2));
        server.enqueue(429, "{\"error\":{\"message\":\"wait\"}}", Map.of("retry-after-ms", "60000"));
        server.respond(200, completedSse());

        var stream = provider.stream(request(), new MutableCancellationSignal());
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertTrue(server.awaitRequests(1, Duration.ofSeconds(3)));
        provider.close();

        var error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertInstanceOf(AssistantMessageEvent.Error.class, stream.take()));
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("provider is closed"));
        assertEquals(1, server.requests().size());

        var closed = drain(provider.stream(request(), new MutableCancellationSignal()));
        assertStartThen(closed, AssistantMessageEvent.Error.class);
        assertTrue(((AssistantMessageEvent.Error) closed.get(1)).error().errorMessage().contains("provider is closed"));
        assertEquals(1, server.requests().size());
    }

    @Test
    void twoXxDisconnectAfterAcceptIsNotRetried() {
        reopen(OpenAiRetryPolicy.of(3));
        server.respond(200, "event: response.created\ndata: {\"response\":{\"id\":\"resp_1\"}}\n\n");

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(1));
        assertTrue(error.error().errorMessage().contains("before terminal"));
        assertEquals(1, server.requests().size());
    }

    @Test
    void eachAttemptReusesTheSameRequestBody() {
        reopen(OpenAiRetryPolicy.of(1));
        server.enqueue(500, "{\"error\":{\"message\":\"again\"}}", Map.of("retry-after-ms", "0"));
        server.respond(200, completedSse());

        drain(provider.stream(request(), new MutableCancellationSignal()));
        assertEquals(2, server.requests().size());
        assertEquals(server.requests().get(0).body(), server.requests().get(1).body());
        assertFalse(server.requests().get(0).body().isBlank());
    }

    @Test
    void lifecycleEmitsStartAndTerminalOnce() {
        reopen(OpenAiRetryPolicy.of(1));
        server.enqueue(503, "{\"error\":{\"message\":\"again\"}}", Map.of("retry-after-ms", "0"));
        server.respond(200, completedSse());

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        assertEquals(1, events.stream().filter(e -> e instanceof AssistantMessageEvent.Start).count());
        assertEquals(1, events.stream().filter(e ->
                e instanceof AssistantMessageEvent.Done || e instanceof AssistantMessageEvent.Error).count());
        assertInstanceOf(AssistantMessageEvent.Done.class, events.get(events.size() - 1));
    }

    @Test
    void standardNonStandardAndOversizeBodies() {
        reopen(OpenAiRetryPolicy.disabled());
        server.respond(429, "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit_error\",\"code\":\"rate_limit_exceeded\"}}");
        var standard = (AssistantMessageEvent.Error) drain(provider.stream(request(), new MutableCancellationSignal())).get(1);
        assertEquals(
                "HTTP 429 rate_limit_error [rate_limit_exceeded]: slow down",
                standard.error().errorMessage());

        server.respond(403, "gateway blocked");
        var raw = (AssistantMessageEvent.Error) drain(provider.stream(request(), new MutableCancellationSignal())).get(1);
        assertEquals("HTTP 403: gateway blocked", raw.error().errorMessage());

        server.respond(500, "z".repeat(OpenAiHttpError.MAX_BODY_BYTES + 64));
        var longBody = (AssistantMessageEvent.Error) drain(provider.stream(request(), new MutableCancellationSignal())).get(1);
        assertTrue(longBody.error().errorMessage().contains("[truncated]"));
        assertTrue(longBody.error().errorMessage().length() < OpenAiHttpError.MAX_BODY_BYTES + 80);
    }

    @Test
    void errorAndConfigDiagnosticsDoNotLeakSecrets() {
        String org = "org-secret-value";
        String project = "proj-secret-value";
        String cache = "cache-secret-value";
        String session = "session-secret-value";
        var headers = OpenAiHeaders.builder().header("X-Trace", "trace-secret-value").build();
        reopen(OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(GPT))
                .baseUrl(server.baseUrl())
                .organization(org)
                .project(project)
                .headers(headers)
                .retryPolicy(OpenAiRetryPolicy.disabled())
                .build());
        server.respond(401, "{\"id\":\"resp_err\",\"error\":{\"message\":\"bad key " + API_KEY + " " + org + " " + project + " " + cache + " " + session + " trace-secret-value\",\"request_id\":\"should-not-copy\"}}",
                Map.of("x-request-id", "req_err", "Authorization", "Bearer leaked"));

        var request = new ModelRequest(GPT.toRef(), "sys", List.of(), List.of(),
                ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withPromptCache(
                        PromptCacheOptions.defaults().withCacheKey(cache).withSessionAffinityId(session)));
        var events = drain(provider.stream(request, new MutableCancellationSignal()));
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, events.get(1));
        String message = error.error().errorMessage();
        assertTrue(message.contains("HTTP 401"));
        assertTrue(message.contains("bad key"));
        assertTrue(message.contains("[redacted]"));
        assertFalse(message.contains(API_KEY));
        assertFalse(message.contains(org));
        assertFalse(message.contains(project));
        assertFalse(message.contains(cache));
        assertFalse(message.contains(session));
        assertFalse(message.contains("trace-secret-value"));
        assertFalse(message.contains("Bearer leaked"));
        assertFalse(error.toString().contains(API_KEY));
        assertEquals("req_err", error.error().metadata().providerRequestId().orElseThrow());
        assertTrue(error.error().metadata().responseId().isEmpty());
        assertTrue(error.error().metadata().rawTerminalReason().isEmpty());
        assertFalse(error.error().metadata().toString().contains("resp_err"));
        assertFalse(error.error().metadata().toString().contains("should-not-copy"));

        String configText = OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(GPT))
                .organization(org)
                .project(project)
                .headers(headers)
                .retryPolicy(OpenAiRetryPolicy.of(1))
                .build()
                .toString();
        assertFalse(configText.contains(API_KEY));
        assertFalse(configText.contains("trace-secret-value"));
    }

    @Test
    void callerCancellationClosesBlockingErrorBody() throws Exception {
        reopen(OpenAiRetryPolicy.of(2));
        var hold = new CountDownLatch(1);
        server.holdBeforeErrorBody(hold);
        server.respond(429, "{\"error\":{\"message\":\"wait\"}}");

        var cancellation = new MutableCancellationSignal();
        var stream = provider.stream(request(), cancellation);
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertTrue(server.headersSent().await(3, java.util.concurrent.TimeUnit.SECONDS));
        cancellation.cancel();

        var error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertInstanceOf(AssistantMessageEvent.Error.class, stream.take()));
        assertEquals(StopReason.ABORTED, error.reason());
        assertEquals(1, hold.getCount());
        assertEquals(1, server.requests().size());
        assertTrue(stream.isDone());
    }

    @Test
    void providerCloseClosesBlockingErrorBody() throws Exception {
        reopen(OpenAiRetryPolicy.of(2));
        var hold = new CountDownLatch(1);
        server.holdBeforeErrorBody(hold);
        server.respond(429, "{\"error\":{\"message\":\"wait\"}}");

        var stream = provider.stream(request(), new MutableCancellationSignal());
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertTrue(server.headersSent().await(3, java.util.concurrent.TimeUnit.SECONDS));
        provider.close();

        var error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertInstanceOf(AssistantMessageEvent.Error.class, stream.take()));
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("provider is closed"));
        assertEquals(1, hold.getCount());
        assertEquals(1, server.requests().size());
        assertTrue(stream.isDone());
    }

    @Test
    void errorBodyAndEnvelopeRedactEachSecretClass() {
        String org = "org-secret-value";
        String project = "proj-secret-value";
        String cache = "cache-secret-value";
        String session = "session-secret-value";
        String trace = "trace-secret-value";
        var headers = OpenAiHeaders.builder().header("X-Trace", trace).build();
        reopen(secretConfig(org, project, headers, OpenAiRetryPolicy.disabled()));

        String echoed = API_KEY + " " + org + " " + project + " " + cache + " " + session + " " + trace;
        var request = secretRequest(cache, session);

        server.respond(401, "{\"error\":{\"message\":\"envelope " + echoed + "\"}}");
        var envelope = (AssistantMessageEvent.Error) drain(provider.stream(request, new MutableCancellationSignal())).get(1);
        assertNoSecrets(envelope.error().errorMessage(), org, project, cache, session, trace);
        assertTrue(envelope.error().errorMessage().contains("HTTP 401"));
        assertTrue(envelope.error().errorMessage().contains("[redacted]"));

        server.respond(403, "raw " + echoed);
        var raw = (AssistantMessageEvent.Error) drain(provider.stream(request, new MutableCancellationSignal())).get(1);
        assertNoSecrets(raw.error().errorMessage(), org, project, cache, session, trace);
        assertTrue(raw.error().errorMessage().contains("[redacted]"));
    }

    @Test
    void sseProviderErrorRedactsSecrets() {
        String org = "org-secret-value";
        String session = "session-secret-value";
        reopen(secretConfig(org, "proj-secret-value",
                OpenAiHeaders.builder().header("X-Trace", "trace-secret-value").build(),
                OpenAiRetryPolicy.disabled()));
        server.respond(200, "event: error\ndata: {\"code\":\"x\",\"message\":\"sse " + API_KEY + " " + org + " " + session + "\"}\n\n");

        var error = (AssistantMessageEvent.Error) drain(provider.stream(
                secretRequest("cache-secret-value", session), new MutableCancellationSignal())).get(1);
        assertNoSecrets(error.error().errorMessage(), org, "proj-secret-value", "cache-secret-value", session, "trace-secret-value");
        assertFalse(error.error().errorMessage().contains(API_KEY));
    }

    @Test
    void oversizedRetryErrorBodyIsClosedBeforeNextAttempt() {
        var first = new CloseTrackingInputStream(
                "z".repeat(OpenAiHttpError.MAX_BODY_BYTES + 64).getBytes(StandardCharsets.UTF_8));
        var client = new ScriptedHttpClient();
        client.enqueue(429, Map.of("retry-after-ms", "0"), first);
        client.enqueue(200, Map.of(), new ByteArrayInputStream(completedSse().getBytes(StandardCharsets.UTF_8)));
        reopen(secretConfig(null, null, OpenAiHeaders.empty(), OpenAiRetryPolicy.of(1)),
                client, OpenAiRetrySupport.createDefault());

        var events = drain(provider.stream(request(), new MutableCancellationSignal()));
        assertStartThen(events, AssistantMessageEvent.Done.class);
        assertTrue(first.isClosed());
        assertEquals(Boolean.TRUE, client.previousTrackedClosedBeforeSend());
        assertEquals(2, client.sendCount());
    }

    @Test
    void cancelDuringErrorBodyReadRemainsTerminalOnce() throws Exception {
        var blocking = new CloseTrackingInputStream("wait".getBytes(StandardCharsets.UTF_8), true);
        var client = new ScriptedHttpClient();
        client.enqueue(429, Map.of("retry-after-ms", "0"), blocking);
        reopen(secretConfig(null, null, OpenAiHeaders.empty(), OpenAiRetryPolicy.of(2)),
                client, OpenAiRetrySupport.createDefault());

        var cancellation = new MutableCancellationSignal();
        var stream = provider.stream(request(), cancellation);
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertTrue(blocking.awaitFirstRead(3, java.util.concurrent.TimeUnit.SECONDS));
        cancellation.cancel();

        var error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertInstanceOf(AssistantMessageEvent.Error.class, stream.take()));
        assertEquals(StopReason.ABORTED, error.reason());
        assertTrue(stream.isDone());
        assertEquals(1, client.sendCount());
        assertTrue(blocking.isClosed());
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertEquals(null, stream.take()));
    }

    @Test
    void httpRequestIdEchoingSecretIsRedactedInMetadata() {
        String session = "session-secret-value";
        String cache = "cache-secret-value";
        reopen(secretConfig("org-secret-value", "proj-secret-value",
                OpenAiHeaders.builder().header("X-Trace", "trace-secret-value").build(),
                OpenAiRetryPolicy.disabled()));
        server.respond(401, "{\"error\":{\"message\":\"denied\"}}", Map.of("x-request-id", session));

        var error = (AssistantMessageEvent.Error) drain(provider.stream(
                secretRequest(cache, session), new MutableCancellationSignal())).get(1);
        assertNoSecrets(
                error.error().errorMessage()
                        + error.error().metadata().toString()
                        + error.error().metadata().responseId().orElse("")
                        + error.error().metadata().providerRequestId().orElse("")
                        + error.error().metadata().rawTerminalReason().orElse(""),
                "org-secret-value",
                "proj-secret-value",
                cache,
                session,
                "trace-secret-value");
        assertTrue(error.error().metadata().providerRequestId().isPresent());
        assertTrue(error.error().metadata().providerRequestId().orElseThrow().contains("[redacted]"));
    }

    @Test
    void sseTerminalIdAndRawReasonEchoingSecretsAreRedacted() {
        String cache = "cache-secret-value";
        String session = "session-secret-value";
        reopen(secretConfig("org-secret-value", "proj-secret-value",
                OpenAiHeaders.builder().header("X-Trace", "trace-secret-value").build(),
                OpenAiRetryPolicy.disabled()));
        server.respond(200,
                "event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{"
                        + "\"id\":\"" + session + "\","
                        + "\"status\":\"" + cache + "\","
                        + "\"error\":{\"code\":\"x\",\"message\":\"failed\"}}}\n\n",
                Map.of("x-request-id", API_KEY));

        var error = (AssistantMessageEvent.Error) drain(provider.stream(
                secretRequest(cache, session), new MutableCancellationSignal())).get(1);
        var metadata = error.error().metadata();
        assertNoSecrets(
                error.error().errorMessage()
                        + metadata.toString()
                        + metadata.responseId().orElse("")
                        + metadata.providerRequestId().orElse("")
                        + metadata.rawTerminalReason().orElse(""),
                "org-secret-value",
                "proj-secret-value",
                cache,
                session,
                "trace-secret-value");
        assertTrue(metadata.responseId().orElseThrow().contains("[redacted]"));
        assertTrue(metadata.providerRequestId().orElseThrow().contains("[redacted]"));
        assertTrue(metadata.rawTerminalReason().orElseThrow().contains("[redacted]"));
    }

    @Test
    void sseEventNameConflictUsesFixedMessageWithoutProviderText() {
        String session = "session-secret-value";
        reopen(secretConfig("org-secret-value", "proj-secret-value",
                OpenAiHeaders.builder().header("X-Trace", "trace-secret-value").build(),
                OpenAiRetryPolicy.disabled()));
        server.respond(200, "event: " + session + "\ndata: {\"type\":\"response.failed\"}\n\n");

        var error = (AssistantMessageEvent.Error) drain(provider.stream(
                secretRequest("cache-secret-value", session), new MutableCancellationSignal())).get(1);
        assertEquals(OpenAiResponsesAdapter.SSE_EVENT_IDENTITY_CONFLICT, error.error().errorMessage());
        assertNoSecrets(error.error().errorMessage(), "org-secret-value", "proj-secret-value",
                "cache-secret-value", session, "trace-secret-value");
    }

    @Test
    void sendBoundaryExceptionIsFixedAndNotRetried() {
        String org = "org-secret-value";
        String project = "proj-secret-value";
        String cache = "cache-secret-value";
        String session = "session-secret-value";
        String trace = "trace-secret-value";
        var headers = OpenAiHeaders.builder().header("X-Trace", trace).build();
        var config = secretConfig(org, project, headers, OpenAiRetryPolicy.of(2));
        if (provider != null) {
            provider.close();
        }
        var client = new ThrowingSendHttpClient(
                new IllegalArgumentException("boom " + API_KEY + " " + org + " " + project + " " + cache + " " + session + " " + trace));
        provider = new OpenAiProvider(config, client, MAPPER);

        var events = drain(provider.stream(secretRequest(cache, session), new MutableCancellationSignal()));
        assertStartThen(events, AssistantMessageEvent.Error.class);
        var error = (AssistantMessageEvent.Error) events.get(1);
        assertEquals("request failed", error.error().errorMessage());
        assertNoSecrets(error.error().errorMessage() + error.toString(), org, project, cache, session, trace);
        assertTrue(server.requests().isEmpty());
        assertEquals(1, client.sendCount());
    }

    @Test
    void sendBoundaryUncheckedIoIsRetriedThenFailsRedacted() {
        String org = "org-secret-value";
        var client = new ThrowingSendHttpClient(
                new java.io.UncheckedIOException(new java.io.IOException("io " + API_KEY + " " + org)));
        if (provider != null) {
            provider.close();
        }
        provider = new OpenAiProvider(
                secretConfig(org, null, OpenAiHeaders.empty(), OpenAiRetryPolicy.of(1)),
                client,
                MAPPER);

        var events = drain(provider.stream(secretRequest("cache-secret-value", "session-secret-value"),
                new MutableCancellationSignal()));
        assertStartThen(events, AssistantMessageEvent.Error.class);
        var error = (AssistantMessageEvent.Error) events.get(1);
        assertEquals("request failed", error.error().errorMessage());
        assertFalse(error.error().errorMessage().contains(API_KEY));
        assertFalse(error.error().errorMessage().contains(org));
        assertEquals(2, client.sendCount());
    }

    @Test
    void schedulerOverrunDoesNotSendAfterBudgetDeadline() throws Exception {
        var harness = new ManualRetrySupport(Instant.parse("2026-01-01T00:00:00Z"));
        reopen(secretConfig(null, null, OpenAiHeaders.empty(),
                OpenAiRetryPolicy.builder()
                        .maxRetries(2)
                        .totalBudget(Duration.ofSeconds(1))
                        .build()),
                HttpClient.newHttpClient(), harness.support());
        server.enqueue(429, "{\"error\":{\"message\":\"wait\"}}", Map.of("retry-after-ms", "100"));
        server.respond(200, completedSse());

        var stream = provider.stream(request(), new MutableCancellationSignal());
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertTrue(server.awaitRequests(1, Duration.ofSeconds(3)));
        assertTrue(harness.awaitPending(Duration.ofSeconds(3)));
        harness.advance(Duration.ofSeconds(2));

        var error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertInstanceOf(AssistantMessageEvent.Error.class, stream.take()));
        assertTrue(error.error().errorMessage().contains("retry budget exhausted"));
        assertEquals(1, server.requests().size());
    }

    @Test
    void zeroDelayAfterBudgetElapsedDoesNotRetry() throws Exception {
        var harness = new ManualRetrySupport(Instant.parse("2026-01-01T00:00:00Z"));
        var hold = new CountDownLatch(1);
        server.holdBeforeHeaders(hold);
        reopen(OpenAiProviderConfig.builder()
                        .credentials(OpenAiCredentials.apiKey(API_KEY))
                        .models(List.of(GPT))
                        .baseUrl(server.baseUrl())
                        .retryPolicy(OpenAiRetryPolicy.builder()
                                .maxRetries(2)
                                .totalBudget(Duration.ofSeconds(1))
                                .build())
                        .build(),
                HttpClient.newHttpClient(), harness.support());
        server.enqueue(429, "{\"error\":{\"message\":\"late\"}}", Map.of("retry-after-ms", "0"));
        server.respond(200, completedSse());

        var stream = provider.stream(request(), new MutableCancellationSignal());
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertTrue(server.awaitRequests(1, Duration.ofSeconds(3)));
        harness.advance(Duration.ofSeconds(2));
        hold.countDown();

        var error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertInstanceOf(AssistantMessageEvent.Error.class, stream.take()));
        assertTrue(error.error().errorMessage().contains("retry budget exhausted"));
        assertEquals(1, server.requests().size());
    }

    @Test
    void illegalDelayHeadersFallBackToBackoff() {
        reopen(OpenAiRetryPolicy.builder()
                .maxRetries(1)
                .maxBackoff(Duration.ZERO)
                .jitterRange(1.0d, 1.0d)
                .build());
        server.enqueue(429, "{\"error\":{\"message\":\"bad headers\"}}",
                Map.of("retry-after-ms", "not-a-number", "Retry-After", "soon"));
        server.respond(200, completedSse());
        assertStartThen(drain(provider.stream(request(), new MutableCancellationSignal())),
                AssistantMessageEvent.Done.class);
        assertEquals(2, server.requests().size());
    }

    private static OpenAiProviderConfig secretConfig(
            String org,
            String project,
            OpenAiHeaders headers,
            OpenAiRetryPolicy policy
    ) {
        var builder = OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(GPT))
                .headers(headers)
                .retryPolicy(policy);
        if (org != null) {
            builder.organization(org);
        }
        if (project != null) {
            builder.project(project);
        }
        return builder.build();
    }

    private static ModelRequest secretRequest(String cache, String session) {
        return new ModelRequest(GPT.toRef(), "sys", List.of(), List.of(),
                ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withPromptCache(
                        PromptCacheOptions.defaults().withCacheKey(cache).withSessionAffinityId(session)));
    }

    private static void assertNoSecrets(String text, String org, String project, String cache, String session, String trace) {
        assertFalse(text.contains(API_KEY));
        if (org != null) {
            assertFalse(text.contains(org));
        }
        if (project != null) {
            assertFalse(text.contains(project));
        }
        if (cache != null) {
            assertFalse(text.contains(cache));
        }
        if (session != null) {
            assertFalse(text.contains(session));
        }
        if (trace != null) {
            assertFalse(text.contains(trace));
        }
    }

    private void reopen(OpenAiRetryPolicy policy) {
        reopen(OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(GPT))
                .baseUrl(server.baseUrl())
                .retryPolicy(policy)
                .build());
    }

    private void reopen(OpenAiProviderConfig config) {
        reopen(config, HttpClient.newHttpClient(), OpenAiRetrySupport.createDefault());
    }

    private void reopen(OpenAiProviderConfig config, HttpClient httpClient, OpenAiRetrySupport retrySupport) {
        if (provider != null) {
            provider.close();
        }
        var builder = OpenAiProviderConfig.builder()
                .credentials(config.credentials())
                .models(config.models())
                .baseUrl(server.baseUrl())
                .headers(config.headers())
                .retryPolicy(config.retryPolicy());
        config.organization().ifPresent(builder::organization);
        config.project().ifPresent(builder::project);
        provider = new OpenAiProvider(builder.build(), httpClient, MAPPER, retrySupport);
    }

    private OpenAiProvider providerWith(OpenAiRetryPolicy policy) {
        return providerWith(OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(GPT))
                .baseUrl(server.baseUrl())
                .retryPolicy(policy)
                .build());
    }

    private static OpenAiProvider providerWith(OpenAiProviderConfig config) {
        return new OpenAiProvider(config, HttpClient.newHttpClient(), MAPPER);
    }

    private static ModelRequest request() {
        return new ModelRequest(GPT.toRef(), "sys", List.of(), List.of());
    }

    private static String completedSse() {
        return "event: response.completed\ndata: {\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}\n\n";
    }

    private static void assertStartThen(List<AssistantMessageEvent> events, Class<?> terminalType) {
        assertInstanceOf(AssistantMessageEvent.Start.class, events.get(0));
        assertEquals(2, events.size());
        assertInstanceOf(terminalType, events.get(1));
    }

    private static List<AssistantMessageEvent> drain(AssistantMessageStream stream) {
        var events = new CopyOnWriteArrayList<AssistantMessageEvent>();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var collected = new ArrayList<AssistantMessageEvent>();
            while (true) {
                var event = stream.take();
                if (event == null) {
                    break;
                }
                collected.add(event);
                if (event instanceof AssistantMessageEvent.Done || event instanceof AssistantMessageEvent.Error) {
                    break;
                }
            }
            events.addAll(collected);
        });
        return List.copyOf(events);
    }
}
