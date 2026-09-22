package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.client.CacheRetention;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationRegistration;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.ResponseMetadata;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;

/**
 * Package-private OpenAI Responses API stream adapter. Turns a validated
 * {@link ModelRequest} into an {@link AssistantMessageStream}: the stream is
 * created and immediately started with an empty {@code Start} event, then a
 * provider-owned virtual-thread executor runs the HTTP/SSE producer. All
 * failures (mapping, HTTP status, transport, malformed SSE, provider errors,
 * cancellation, provider close) are normalized into a terminal
 * {@code Done}/{@code Error} event; nothing throws synchronously from
 * {@link #stream}. Opt-in retries never emit a second {@code Start} and
 * never reuse an event mapper after a failed attempt.
 */
final class OpenAiResponsesAdapter implements AutoCloseable {
    private static final String ENDPOINT_PATH = "responses";
    static final String REQUEST_FAILED = "request failed";
    static final String REQUEST_MAPPING_FAILED = "request mapping failed";
    static final String EVENT_MAPPING_FAILED = "event mapping failed";
    static final String MALFORMED_SSE_DATA = "malformed SSE data";
    static final String STREAM_READ_FAILED = "stream read failed";
    static final String UNEXPECTED_ADAPTER_FAILURE = "unexpected adapter failure";
    static final String RETRY_BUDGET_EXHAUSTED = "retry budget exhausted";
    static final String SERVER_DELAY_EXCEEDS_MAXIMUM = "server requested retry delay exceeds maximum";
    static final String SSE_EVENT_IDENTITY_CONFLICT = "SSE event name and JSON type conflict";

    private final OpenAiProviderConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenAiRequestMapper requestMapper;
    private final OpenAiRetrySupport retrySupport;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object adapterLock = new Object();
    private final Set<ActiveExchange> active = new HashSet<>();
    private boolean closed;

    OpenAiResponsesAdapter(OpenAiProviderConfig config, HttpClient httpClient, ObjectMapper objectMapper) {
        this(config, httpClient, objectMapper, OpenAiRetrySupport.createDefault());
    }

    OpenAiResponsesAdapter(
            OpenAiProviderConfig config,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            OpenAiRetrySupport retrySupport
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.retrySupport = Objects.requireNonNull(retrySupport, "retrySupport must not be null");
        this.requestMapper = new OpenAiRequestMapper(objectMapper);
    }

    AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
        var stream = new AssistantMessageStream();
        var start = new Message.Assistant(
                List.of(), StopReason.STOP, null, Usage.zero(), Instant.now(), request.model());
        stream.push(new AssistantMessageEvent.Start(start));
        var exchange = new ActiveExchange(stream, request.model(), OpenAiSecretRedactor.collect(config, request));
        if (!addActive(exchange)) {
            exchange.completeOnce(StopReason.ERROR, "provider is closed");
            return stream;
        }
        try {
            exchange.bindCancellation(cancellation);
            executor.execute(() -> produce(exchange, request, cancellation));
        } catch (RejectedExecutionException e) {
            exchange.completeOnce(StopReason.ERROR, "provider is closed");
            exchange.finish();
        }
        return stream;
    }

    private void produce(ActiveExchange exchange, ModelRequest request, CancellationSignal cancellation) {
        try {
            if (exchange.isAborted() || cancellation.isCancelled()) {
                exchange.abortFromCaller("cancelled before request");
                return;
            }
            JsonNode payload;
            try {
                payload = requestMapper.map(
                        request,
                        capabilitiesFor(request.model().modelId()),
                        config.compatibility(),
                        config.serviceTier().orElse(null));
            } catch (RuntimeException e) {
                exchange.completeOnce(StopReason.ERROR, REQUEST_MAPPING_FAILED);
                return;
            }
            if (exchange.isAborted() || cancellation.isCancelled()) {
                exchange.abortFromCaller("cancelled after request mapping");
                return;
            }
            byte[] json;
            try {
                json = writeBytes(payload);
            } catch (RuntimeException e) {
                exchange.completeOnce(StopReason.ERROR, REQUEST_MAPPING_FAILED);
                return;
            }
            OpenAiRetryPolicy policy = config.retryPolicy();
            Instant started = retrySupport.now();
            int retriesRemaining = policy.maxRetries();
            int retryIndex = 0;
            boolean firstAttempt = true;
            while (true) {
                if (isClosed()) {
                    exchange.abortFromProviderClose();
                    return;
                }
                if (exchange.isAborted() || cancellation.isCancelled()) {
                    exchange.abortFromCaller("cancelled");
                    return;
                }
                if (!firstAttempt && OpenAiRetry.budgetExhausted(started, retrySupport.now(), policy.totalBudget())) {
                    exchange.completeOnce(StopReason.ERROR, RETRY_BUDGET_EXHAUSTED);
                    return;
                }
                HttpRequest httpRequest;
                try {
                    httpRequest = buildRequest(json, request);
                } catch (RuntimeException e) {
                    // Header values (session, custom, organization, project) must never
                    // enter terminal events. JDK HttpRequest may echo the illegal value.
                    exchange.completeOnce(StopReason.ERROR, "request headers could not be applied");
                    return;
                }
                CompletableFuture<HttpResponse<InputStream>> httpFuture;
                try {
                    httpFuture = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                } catch (RuntimeException e) {
                    if (exchange.isAborted() || cancellation.isCancelled() || isClosed()) {
                        exchange.completeAfterIoFailure(cancellation, REQUEST_FAILED);
                        return;
                    }
                    if (OpenAiRetry.isSynchronousTransportFailure(e)
                            && retriesRemaining > 0
                            && OpenAiRetry.isRetryableTransport()) {
                        firstAttempt = false;
                        OpenAiRetry.Delay delay = OpenAiRetry.delayForTransport(
                                retryIndex, policy, retrySupport.nextJitterUnit());
                        if (!beginRetry(exchange, cancellation, policy, started, delay, null)) {
                            return;
                        }
                        retriesRemaining--;
                        retryIndex++;
                        continue;
                    }
                    exchange.completeOnce(StopReason.ERROR, REQUEST_FAILED);
                    return;
                }
                firstAttempt = false;
                exchange.attachHttpFuture(httpFuture);
                HttpResponse<InputStream> response;
                try {
                    response = httpFuture.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    exchange.completeAfterIoFailure(cancellation, "interrupted while sending request");
                    return;
                } catch (CancellationException e) {
                    exchange.completeAfterIoFailure(cancellation, "cancelled while sending request");
                    return;
                } catch (ExecutionException e) {
                    if (exchange.isAborted() || cancellation.isCancelled() || isClosed()) {
                        exchange.completeAfterIoFailure(cancellation, REQUEST_FAILED);
                        return;
                    }
                    if (retriesRemaining <= 0 || !OpenAiRetry.isRetryableTransport()) {
                        exchange.completeOnce(StopReason.ERROR, REQUEST_FAILED);
                        return;
                    }
                    OpenAiRetry.Delay delay = OpenAiRetry.delayForTransport(
                            retryIndex, policy, retrySupport.nextJitterUnit());
                    if (!beginRetry(exchange, cancellation, policy, started, delay, null)) {
                        return;
                    }
                    retriesRemaining--;
                    retryIndex++;
                    continue;
                }
                if (exchange.isAborted()) {
                    exchange.completeAfterIoFailure(cancellation, "cancelled after response headers");
                    closeQuietly(response.body());
                    return;
                }
                if (isSuccessStatus(response.statusCode())) {
                    consumeAcceptedSse(exchange, request, cancellation, response);
                    return;
                }
                byte[] raw;
                try {
                    raw = readErrorBody(exchange, response.body());
                } catch (IOException e) {
                    exchange.completeAfterIoFailure(cancellation, REQUEST_FAILED);
                    return;
                }
                if (exchange.isAborted() || cancellation.isCancelled() || isClosed()) {
                    exchange.completeAfterIoFailure(cancellation, REQUEST_FAILED);
                    return;
                }
                OpenAiHttpError error = OpenAiHttpError.parse(
                        response.statusCode(),
                        response.headers(),
                        raw,
                        objectMapper,
                        exchange.redactor());
                exchange.acceptCorrelation(OpenAiResponseCorrelation.fromHttpFailure(error.requestId()));
                if (retriesRemaining <= 0 || !OpenAiRetry.isRetryable(error)) {
                    var metadata = ResponseMetadata.of(
                            null,
                            error.requestId().orElse(null),
                            null,
                            error.failureKind().orElse(null));
                    exchange.completeOnce(StopReason.ERROR, error.diagnosticMessage(), metadata);
                    return;
                }
                OpenAiRetry.Delay delay = OpenAiRetry.delayForHttp(
                        error, retryIndex, policy, retrySupport.now(), retrySupport.nextJitterUnit());
                if (!beginRetry(exchange, cancellation, policy, started, delay, error)) {
                    return;
                }
                retriesRemaining--;
                retryIndex++;
            }
        } catch (RuntimeException e) {
            // Safety net: never leak an exception or secret out of the producer.
            exchange.completeOnce(StopReason.ERROR, UNEXPECTED_ADAPTER_FAILURE);
        } finally {
            exchange.finish();
        }
    }

    /**
     * A 2xx response has been accepted. EOF, parse, mapping, and read
     * failures from this point are terminal and must not retry.
     */
    private void consumeAcceptedSse(
            ActiveExchange exchange,
            ModelRequest request,
            CancellationSignal cancellation,
            HttpResponse<InputStream> response
    ) {
        Optional<String> requestId = OpenAiResponseCorrelation.providerRequestId(response.headers());
        exchange.acceptCorrelation(OpenAiResponseCorrelation.fromHttpFailure(requestId));
        var mapper = new OpenAiEventMapper(
                request.model(),
                OpenAiConstrainedSampling.grammarInputProperties(
                        request.tools(), config.compatibility().grammarTools()),
                config.pricing(),
                config.serviceTier());
        mapper.acceptProviderRequestId(requestId);
        InputStream in = response.body();
        exchange.attachBody(in);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            consumeSse(reader, exchange, mapper, cancellation);
        } catch (IOException e) {
            exchange.completeAfterIoFailure(cancellation, STREAM_READ_FAILED);
        } finally {
            exchange.releaseBody(in);
        }
    }

    private boolean beginRetry(
            ActiveExchange exchange,
            CancellationSignal cancellation,
            OpenAiRetryPolicy policy,
            Instant started,
            OpenAiRetry.Delay delay,
            OpenAiHttpError error
    ) {
        if (delay.exceedsServerMax()) {
            String suffix = error == null ? "" : ": " + error.diagnosticMessage();
            exchange.completeOnce(StopReason.ERROR, SERVER_DELAY_EXCEEDS_MAXIMUM + suffix);
            return false;
        }
        if (OpenAiRetry.exceedsRemainingBudget(delay.duration(), started, retrySupport.now(), policy.totalBudget())) {
            String suffix = error == null ? "" : ": " + error.diagnosticMessage();
            exchange.completeOnce(StopReason.ERROR, RETRY_BUDGET_EXHAUSTED + suffix);
            return false;
        }
        if (isClosed()) {
            exchange.abortFromProviderClose();
            return false;
        }
        if (exchange.isAborted() || cancellation.isCancelled()) {
            exchange.abortFromCaller("cancelled");
            return false;
        }
        if (!awaitRetryDelay(exchange, cancellation, delay.duration())) {
            return false;
        }
        if (OpenAiRetry.budgetExhausted(started, retrySupport.now(), policy.totalBudget())) {
            String suffix = error == null ? "" : ": " + error.diagnosticMessage();
            exchange.completeOnce(StopReason.ERROR, RETRY_BUDGET_EXHAUSTED + suffix);
            return false;
        }
        return true;
    }

    /**
     * Cancellation-aware backoff. The cancellation listener only cancels the
     * timer/future. {@link Thread#sleep} is never used.
     */
    private boolean awaitRetryDelay(
            ActiveExchange exchange,
            CancellationSignal cancellation,
            Duration delay
    ) {
        if (delay.isZero() || delay.isNegative()) {
            if (isClosed()) {
                exchange.abortFromProviderClose();
                return false;
            }
            if (exchange.isAborted() || cancellation.isCancelled()) {
                exchange.abortFromCaller("cancelled");
                return false;
            }
            return true;
        }
        var wait = new CompletableFuture<Void>();
        ScheduledFuture<?> timer;
        try {
            timer = retrySupport.schedule(() -> wait.complete(null), delay);
        } catch (RejectedExecutionException e) {
            exchange.abortFromProviderClose();
            return false;
        }
        exchange.attachRetryWait(timer, wait);
        try {
            wait.get();
            if (isClosed()) {
                exchange.abortFromProviderClose();
                return false;
            }
            if (exchange.isAborted() || cancellation.isCancelled()) {
                exchange.abortFromCaller("cancelled");
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.completeAfterIoFailure(cancellation, "interrupted while waiting to retry");
            return false;
        } catch (CancellationException | ExecutionException e) {
            exchange.completeAfterIoFailure(cancellation, "cancelled while waiting to retry");
            return false;
        } finally {
            timer.cancel(false);
            exchange.clearRetryWait();
        }
    }

    private OpenAiModelCapabilities capabilitiesFor(String modelId) {
        return config.capabilities().get(modelId);
    }

    private void consumeSse(
            BufferedReader reader,
            ActiveExchange exchange,
            OpenAiEventMapper mapper,
            CancellationSignal cancellation
    ) throws IOException {
        var parser = new OpenAiSseParser();
        try {
            parser.parse(reader, (eventName, data) -> {
                if (exchange.isAborted() || cancellation.isCancelled()) {
                    throw new CancelledException();
                }
                if (data.equals("[DONE]")) {
                    throw new DoneMarkerException();
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(data);
                } catch (IOException e) {
                    throw new SseFormatException(MALFORMED_SSE_DATA);
                }
                if (node == null || !node.isObject()) {
                    return;
                }
                String semanticName = semanticEventName(eventName, node);
                if (semanticName == null) {
                    return;
                }
                List<AssistantMessageEvent> mapped;
                try {
                    mapped = mapper.onEvent(semanticName, node);
                    exchange.acceptCorrelation(mapper.correlationSnapshot());
                } catch (RuntimeException e) {
                    exchange.acceptCorrelation(mapper.correlationSnapshot());
                    throw new MappingException(EVENT_MAPPING_FAILED);
                }
                for (AssistantMessageEvent event : mapped) {
                    if (event instanceof AssistantMessageEvent.Done
                            || event instanceof AssistantMessageEvent.Error) {
                        exchange.completeOnce(event);
                        throw new TerminalPushedException();
                    }
                    exchange.emitNonTerminal(event);
                }
            });
        } catch (CancelledException e) {
            exchange.abortFromCaller("cancelled during stream");
            return;
        } catch (DoneMarkerException e) {
            if (!mapper.terminalHandled()) {
                exchange.completeOnce(StopReason.ERROR, "stream ended ([DONE]) before terminal response");
            }
            return;
        } catch (SseFormatException | MappingException e) {
            exchange.completeOnce(StopReason.ERROR, e.getMessage());
            return;
        } catch (TerminalPushedException e) {
            return;
        }
        if (!mapper.terminalHandled()) {
            exchange.completeAfterIoFailure(cancellation, "stream ended before terminal response");
        }
    }

    static String semanticEventName(String sseName, JsonNode node) {
        String jsonType = node != null && node.path("type").isTextual() ? node.get("type").asText() : null;
        if (jsonType != null && jsonType.isBlank()) {
            jsonType = null;
        }
        boolean generic = sseName == null || sseName.isBlank() || "message".equals(sseName);
        if (generic) {
            return jsonType;
        }
        if (jsonType == null || jsonType.equals(sseName)) {
            return sseName;
        }
        throw new MappingException(SSE_EVENT_IDENTITY_CONFLICT);
    }

    private HttpRequest buildRequest(byte[] json, ModelRequest request) {
        String base = config.baseUrl().toString();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        URI endpoint = URI.create(base).resolve(ENDPOINT_PATH);
        String apiKey = config.credentials().apiKey();
        OpenAiRequestUnicode.requireWellFormedIdentity(apiKey, "header value");
        var builder = HttpRequest.newBuilder(endpoint)
                .timeout(config.requestTimeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json));
        config.organization().ifPresent(o -> {
            OpenAiRequestUnicode.requireWellFormedIdentity(o, "header value");
            builder.header("OpenAI-Organization", o);
        });
        config.project().ifPresent(p -> {
            OpenAiRequestUnicode.requireWellFormedIdentity(p, "header value");
            builder.header("OpenAI-Project", p);
        });
        applySessionHeaders(builder, request);
        config.headers().applyTo(builder);
        return builder.build();
    }

    /**
     * Session-affinity headers follow the explicit endpoint profile. Retention
     * {@code NONE} omits every affinity header. Header values are never copied
     * into exceptions or events.
     */
    private void applySessionHeaders(HttpRequest.Builder builder, ModelRequest request) {
        var cache = request.options().promptCache();
        if (cache.retention() == CacheRetention.NONE) {
            return;
        }
        String sessionId = cache.sessionAffinityId();
        if (sessionId == null) {
            return;
        }
        OpenAiRequestUnicode.requireWellFormedIdentity(sessionId, "header value");
        switch (config.compatibility().endpointProfile()) {
            case OPENAI -> {
                builder.header("session_id", sessionId);
                builder.header("x-client-request-id", sessionId);
            }
            case OPENAI_NO_SESSION -> builder.header("x-client-request-id", sessionId);
            case OPENROUTER -> builder.header("x-session-id", sessionId);
        }
    }

    private byte[] writeBytes(JsonNode payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new IllegalStateException("failed to serialize request payload", e);
        }
    }

    private boolean addActive(ActiveExchange exchange) {
        synchronized (adapterLock) {
            if (closed) {
                return false;
            }
            active.add(exchange);
            return true;
        }
    }

    private void removeActive(ActiveExchange exchange) {
        synchronized (adapterLock) {
            active.remove(exchange);
        }
    }

    private boolean isClosed() {
        synchronized (adapterLock) {
            return closed;
        }
    }

    private static boolean isSuccessStatus(int status) {
        return status >= 200 && status < 300;
    }

    /**
     * Attaches a non-2xx body so cancel/close can unblock a read, then
     * reads at most {@link OpenAiHttpError#MAX_BODY_BYTES} plus one and
     * immediately releases the stream so leftover bytes cannot pin the
     * connection across backoff. The caller must treat {@link IOException}
     * as abort/IO, never as a retry signal.
     */
    private static byte[] readErrorBody(ActiveExchange exchange, InputStream body) throws IOException {
        exchange.attachBody(body);
        try {
            return body.readNBytes(OpenAiHttpError.MAX_BODY_BYTES + 1);
        } finally {
            exchange.releaseBody(body);
        }
    }

    private static void closeQuietly(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException ignored) {
            // Closing an already-aborted body is best-effort.
        }
    }

    @Override
    public void close() {
        List<ActiveExchange> exchanges;
        synchronized (adapterLock) {
            if (closed) {
                return;
            }
            closed = true;
            exchanges = List.copyOf(active);
            active.clear();
        }
        for (ActiveExchange exchange : exchanges) {
            exchange.abortFromProviderClose();
        }
        retrySupport.close();
        executor.shutdownNow();
    }

    private enum AbortCause {
        CALLER,
        PROVIDER_CLOSED
    }

    /**
     * One in-flight HTTP/SSE exchange. Cancellation, provider close, and the
     * producer share a single terminal-once path and an immutable partial
     * snapshot. The cancellation listener only marks state, completes the
     * terminal, and cancels/closes I/O.
     */
    private final class ActiveExchange {
        private final AssistantMessageStream stream;
        private final ModelRef sourceModel;
        private final OpenAiSecretRedactor redactor;
        private final Object lock = new Object();
        private boolean completed;
        private List<Content> lastPartial = List.of();
        private ResponseMetadata knownMetadata = ResponseMetadata.empty();
        private AbortCause abortCause;
        private CompletableFuture<HttpResponse<InputStream>> httpFuture;
        private InputStream activeBody;
        private ScheduledFuture<?> retryTimer;
        private CompletableFuture<Void> retryWait;
        private CancellationRegistration registration;

        ActiveExchange(AssistantMessageStream stream, ModelRef sourceModel, OpenAiSecretRedactor redactor) {
            this.stream = stream;
            this.sourceModel = sourceModel;
            this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
        }

        OpenAiSecretRedactor redactor() {
            return redactor;
        }

        void bindCancellation(CancellationSignal cancellation) {
            registration = cancellation.onCancellation(() -> abortFromCaller("cancelled"));
        }

        void emitNonTerminal(AssistantMessageEvent event) {
            synchronized (lock) {
                if (completed) {
                    return;
                }
                stream.push(event);
                lastPartial = List.copyOf(event.partial().content());
            }
        }

        boolean completeOnce(AssistantMessageEvent terminal) {
            AssistantMessageEvent safe = redactor.redactTerminal(terminal);
            synchronized (lock) {
                if (completed) {
                    return false;
                }
                completed = true;
                lastPartial = List.copyOf(safe.partial().content());
                stream.push(safe);
                return true;
            }
        }

        /**
         * Copies the mapper's producer-thread correlation snapshot for use by
         * cancel/close or later synthetic terminals. The snapshot is bounded
         * {@link ResponseMetadata} only.
         */
        void acceptCorrelation(ResponseMetadata snapshot) {
            synchronized (lock) {
                if (completed) {
                    return;
                }
                this.knownMetadata = redactor.redactMetadata(snapshot);
            }
        }

        boolean completeOnce(StopReason reason, String message) {
            ResponseMetadata metadata;
            synchronized (lock) {
                metadata = knownMetadata;
            }
            return completeOnce(reason, message, metadata);
        }

        boolean completeOnce(StopReason reason, String message, ResponseMetadata metadata) {
            synchronized (lock) {
                if (completed) {
                    return false;
                }
                completed = true;
                ResponseMetadata safe = redactor.redactMetadata(metadata);
                var error = new Message.Assistant(
                        List.copyOf(lastPartial),
                        reason,
                        redactor.redact(message),
                        Usage.zero(),
                        Instant.now(),
                        sourceModel,
                        safe);
                stream.push(new AssistantMessageEvent.Error(reason, error));
                return true;
            }
        }

        void abortFromCaller(String message) {
            markAbort(AbortCause.CALLER);
            completeOnce(StopReason.ABORTED, message);
            cancelIo();
        }

        void abortFromProviderClose() {
            markAbort(AbortCause.PROVIDER_CLOSED);
            completeOnce(StopReason.ERROR, "provider is closed");
            cancelIo();
        }

        void completeAfterIoFailure(CancellationSignal cancellation, String errorMessage) {
            AbortCause cause = abortCause();
            if (cause == AbortCause.CALLER || cancellation.isCancelled()) {
                abortFromCaller("cancelled");
            } else if (cause == AbortCause.PROVIDER_CLOSED) {
                abortFromProviderClose();
            } else {
                completeOnce(StopReason.ERROR, errorMessage);
            }
        }

        void attachHttpFuture(CompletableFuture<HttpResponse<InputStream>> future) {
            boolean abort;
            synchronized (lock) {
                httpFuture = future;
                abort = abortCause != null || completed;
            }
            if (abort) {
                future.cancel(true);
            }
        }

        void attachBody(InputStream body) {
            InputStream previous;
            boolean abort;
            synchronized (lock) {
                previous = activeBody;
                activeBody = body;
                abort = abortCause != null || completed;
            }
            closeQuietly(previous);
            if (abort) {
                releaseBody(body);
            }
        }

        /**
         * Identity-safe detach and close. Only clears {@code activeBody} when
         * it is still this stream so a concurrent cancel or later attempt
         * cannot have its body closed by this release.
         */
        void releaseBody(InputStream body) {
            if (body == null) {
                return;
            }
            synchronized (lock) {
                if (activeBody == body) {
                    activeBody = null;
                }
            }
            closeQuietly(body);
        }

        void attachRetryWait(ScheduledFuture<?> timer, CompletableFuture<Void> wait) {
            boolean abort;
            synchronized (lock) {
                retryTimer = timer;
                retryWait = wait;
                abort = abortCause != null || completed;
            }
            if (abort) {
                timer.cancel(false);
                wait.completeExceptionally(new CancellationException("aborted"));
            }
        }

        void clearRetryWait() {
            synchronized (lock) {
                retryTimer = null;
                retryWait = null;
            }
        }

        boolean isAborted() {
            return abortCause() != null;
        }

        AbortCause abortCause() {
            synchronized (lock) {
                return abortCause;
            }
        }

        void finish() {
            if (registration != null) {
                registration.close();
            }
            removeActive(this);
            InputStream body;
            synchronized (lock) {
                body = activeBody;
                activeBody = null;
            }
            closeQuietly(body);
        }

        private void markAbort(AbortCause cause) {
            synchronized (lock) {
                if (abortCause == null) {
                    abortCause = cause;
                }
            }
        }

        private void cancelIo() {
            CompletableFuture<HttpResponse<InputStream>> future;
            InputStream body;
            ScheduledFuture<?> timer;
            CompletableFuture<Void> wait;
            synchronized (lock) {
                future = httpFuture;
                body = activeBody;
                activeBody = null;
                timer = retryTimer;
                wait = retryWait;
            }
            if (future != null) {
                future.cancel(true);
            }
            closeQuietly(body);
            if (timer != null) {
                timer.cancel(false);
            }
            if (wait != null) {
                wait.completeExceptionally(new CancellationException("aborted"));
            }
        }
    }

    private static final class CancelledException extends RuntimeException {
    }

    private static final class DoneMarkerException extends RuntimeException {
    }

    private static final class SseFormatException extends RuntimeException {
        SseFormatException(String message) {
            super(message);
        }
    }

    private static final class MappingException extends RuntimeException {
        MappingException(String message) {
            super(message);
        }
    }

    private static final class TerminalPushedException extends RuntimeException {
    }
}
