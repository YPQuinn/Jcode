package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationRegistration;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
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
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Package-private OpenAI Responses API stream adapter. Turns a validated
 * {@link ModelRequest} into an {@link AssistantMessageStream}: the stream is
 * created and immediately started with an empty {@code Start} event, then a
 * provider-owned virtual-thread executor runs the HTTP/SSE producer. All
 * failures (mapping, HTTP status, transport, malformed SSE, provider errors,
 * cancellation, provider close) are normalized into a terminal
 * {@code Done}/{@code Error} event; nothing throws synchronously from
 * {@link #stream}.
 */
final class OpenAiResponsesAdapter implements AutoCloseable {
    private static final String ENDPOINT_PATH = "responses";
    private static final int MAX_ERROR_BODY = 500;

    private final OpenAiProviderConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenAiRequestMapper requestMapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object adapterLock = new Object();
    private final Set<ActiveExchange> active = new HashSet<>();
    private boolean closed;

    OpenAiResponsesAdapter(OpenAiProviderConfig config, HttpClient httpClient, ObjectMapper objectMapper) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.requestMapper = new OpenAiRequestMapper(objectMapper);
    }

    AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
        var stream = new AssistantMessageStream();
        var start = new Message.Assistant(
                List.of(), StopReason.STOP, null, Usage.zero(), Instant.now(), request.model());
        stream.push(new AssistantMessageEvent.Start(start));
        var exchange = new ActiveExchange(stream, request.model());
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
                payload = requestMapper.map(request, capabilitiesFor(request.model().modelId()));
            } catch (RuntimeException e) {
                exchange.completeOnce(StopReason.ERROR, "request mapping failed: " + e.getMessage());
                return;
            }
            if (exchange.isAborted() || cancellation.isCancelled()) {
                exchange.abortFromCaller("cancelled after request mapping");
                return;
            }
            var httpFuture = httpClient.sendAsync(buildRequest(payload), HttpResponse.BodyHandlers.ofInputStream());
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
                exchange.completeAfterIoFailure(cancellation, "request failed: " + rootMessage(e));
                return;
            }
            if (exchange.isAborted()) {
                exchange.completeAfterIoFailure(cancellation, "cancelled after response headers");
                closeQuietly(response.body());
                return;
            }
            if (response.statusCode() != 200) {
                String body = readBody(response.body());
                exchange.completeOnce(StopReason.ERROR, "HTTP " + response.statusCode()
                        + (body.isBlank() ? "" : ": " + truncate(body, MAX_ERROR_BODY)));
                return;
            }
            var mapper = new OpenAiEventMapper(request.model());
            InputStream in = response.body();
            exchange.attachBody(in);
            try (in; BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                consumeSse(reader, exchange, mapper, cancellation);
            } catch (IOException e) {
                exchange.completeAfterIoFailure(cancellation, "stream read failed: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            // Safety net: never leak an exception out of the producer.
            exchange.completeOnce(StopReason.ERROR, "unexpected adapter failure: " + e.getMessage());
        } finally {
            exchange.finish();
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
                    throw new SseFormatException("malformed SSE data: " + e.getMessage());
                }
                if (node == null || !node.isObject()) {
                    return;
                }
                String semanticName;
                try {
                    semanticName = semanticEventName(eventName, node);
                } catch (EventIdentityException e) {
                    throw new MappingException(e.getMessage());
                }
                if (semanticName == null) {
                    return;
                }
                List<AssistantMessageEvent> mapped;
                try {
                    mapped = mapper.onEvent(semanticName, node);
                } catch (RuntimeException e) {
                    throw new MappingException("event mapping failed: " + e.getMessage());
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
        throw new EventIdentityException(
                "SSE event name and JSON type conflict: " + sseName + " vs " + jsonType);
    }

    private HttpRequest buildRequest(JsonNode payload) {
        String base = config.baseUrl().toString();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        URI endpoint = URI.create(base).resolve(ENDPOINT_PATH);
        var builder = HttpRequest.newBuilder(endpoint)
                .timeout(config.requestTimeout())
                .header("Authorization", "Bearer " + config.credentials().apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(writeBytes(payload)));
        config.organization().ifPresent(o -> builder.header("OpenAI-Organization", o));
        config.project().ifPresent(p -> builder.header("OpenAI-Project", p));
        return builder.build();
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

    private static String readBody(InputStream body) {
        try (body) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static String rootMessage(ExecutionException e) {
        Throwable cause = e.getCause();
        return cause == null || cause.getMessage() == null ? e.getMessage() : cause.getMessage();
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
        private final Object lock = new Object();
        private boolean completed;
        private List<Content> lastPartial = List.of();
        private AbortCause abortCause;
        private CompletableFuture<HttpResponse<InputStream>> httpFuture;
        private InputStream activeBody;
        private CancellationRegistration registration;

        ActiveExchange(AssistantMessageStream stream, ModelRef sourceModel) {
            this.stream = stream;
            this.sourceModel = sourceModel;
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
            synchronized (lock) {
                if (completed) {
                    return false;
                }
                completed = true;
                lastPartial = List.copyOf(terminal.partial().content());
                stream.push(terminal);
                return true;
            }
        }

        boolean completeOnce(StopReason reason, String message) {
            synchronized (lock) {
                if (completed) {
                    return false;
                }
                completed = true;
                var error = new Message.Assistant(
                        List.copyOf(lastPartial), reason, message, Usage.zero(), Instant.now(), sourceModel);
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
            boolean abort;
            synchronized (lock) {
                activeBody = body;
                abort = abortCause != null || completed;
            }
            if (abort) {
                closeQuietly(body);
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
            synchronized (lock) {
                future = httpFuture;
                body = activeBody;
            }
            if (future != null) {
                future.cancel(true);
            }
            closeQuietly(body);
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

    private static final class EventIdentityException extends RuntimeException {
        EventIdentityException(String message) {
            super(message);
        }
    }

    private static final class TerminalPushedException extends RuntimeException {
    }
}
