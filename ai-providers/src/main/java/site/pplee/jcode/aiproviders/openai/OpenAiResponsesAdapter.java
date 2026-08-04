package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Package-private OpenAI Responses API stream adapter. Turns a validated
 * {@link ModelRequest} into an {@link AssistantMessageStream}: the stream is
 * created and immediately started with an empty {@code Start} event, then a
 * provider-owned virtual-thread executor runs the HTTP/SSE producer. All
 * failures (mapping, HTTP status, transport, malformed SSE, provider errors,
 * cancellation) are normalized into a terminal {@code Error} event; nothing
 * throws synchronously from {@link #stream}.
 */
final class OpenAiResponsesAdapter implements AutoCloseable {
    private static final String ENDPOINT_PATH = "responses";
    private static final int MAX_ERROR_BODY = 500;

    private final OpenAiProviderConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenAiRequestMapper requestMapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    OpenAiResponsesAdapter(OpenAiProviderConfig config, HttpClient httpClient, ObjectMapper objectMapper) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.requestMapper = new OpenAiRequestMapper(objectMapper);
    }

    AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
        var stream = new AssistantMessageStream();
        var start = new Message.Assistant(List.of(), StopReason.STOP, null, Usage.zero(), Instant.now());
        stream.push(new AssistantMessageEvent.Start(start));
        try {
            executor.execute(() -> produce(stream, request, cancellation));
        } catch (RejectedExecutionException e) {
            fail(stream, StopReason.ERROR, "provider is closed", List.of());
        }
        return stream;
    }

    private void produce(AssistantMessageStream stream, ModelRequest request, CancellationSignal cancellation) {
        try {
            if (cancellation.isCancelled()) {
                fail(stream, StopReason.ABORTED, "cancelled before request", List.of());
                return;
            }
            JsonNode payload;
            try {
                payload = requestMapper.map(request, capabilitiesFor(request.model().modelId()));
            } catch (RuntimeException e) {
                fail(stream, StopReason.ERROR, "request mapping failed: " + e.getMessage(), List.of());
                return;
            }
            if (cancellation.isCancelled()) {
                fail(stream, StopReason.ABORTED, "cancelled after request mapping", List.of());
                return;
            }
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(buildRequest(payload), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(stream, StopReason.ABORTED, "interrupted while sending request", List.of());
                return;
            } catch (IOException e) {
                fail(stream, StopReason.ERROR, "request failed: " + e.getMessage(), List.of());
                return;
            }
            if (response.statusCode() != 200) {
                String body = readBody(response.body());
                fail(stream, StopReason.ERROR, "HTTP " + response.statusCode()
                        + (body.isBlank() ? "" : ": " + truncate(body, MAX_ERROR_BODY)), List.of());
                return;
            }
            var mapper = new OpenAiEventMapper();
            try (InputStream in = response.body();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                consumeSse(reader, stream, mapper, cancellation);
            } catch (IOException e) {
                if (cancellation.isCancelled()) {
                    fail(stream, StopReason.ABORTED, "cancelled: " + e.getMessage(), mapper.content());
                } else {
                    fail(stream, StopReason.ERROR, "stream read failed: " + e.getMessage(), mapper.content());
                }
            }
        } catch (RuntimeException e) {
            // Safety net: never leak an exception out of the producer.
            fail(stream, StopReason.ERROR, "unexpected adapter failure: " + e.getMessage(), List.of());
        }
    }

    private OpenAiModelCapabilities capabilitiesFor(String modelId) {
        return config.capabilities().get(modelId);
    }

    private void consumeSse(
            BufferedReader reader,
            AssistantMessageStream stream,
            OpenAiEventMapper mapper,
            CancellationSignal cancellation
    ) throws IOException {
        var parser = new OpenAiSseParser();
        try {
            parser.parse(reader, (eventName, data) -> {
                if (cancellation.isCancelled()) {
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
                List<AssistantMessageEvent> mapped;
                try {
                    mapped = mapper.onEvent(eventName, node);
                } catch (RuntimeException e) {
                    throw new MappingException("event mapping failed: " + e.getMessage());
                }
                for (AssistantMessageEvent event : mapped) {
                    stream.push(event);
                    if (event instanceof AssistantMessageEvent.Done || event instanceof AssistantMessageEvent.Error) {
                        throw new TerminalPushedException();
                    }
                }
            });
        } catch (CancelledException e) {
            fail(stream, StopReason.ABORTED, "cancelled during stream", mapper.content());
            return;
        } catch (DoneMarkerException e) {
            if (!mapper.terminalHandled()) {
                fail(stream, StopReason.ERROR, "stream ended ([DONE]) before terminal response", mapper.content());
            }
            return;
        } catch (SseFormatException e) {
            fail(stream, StopReason.ERROR, e.getMessage(), mapper.content());
            return;
        } catch (MappingException e) {
            fail(stream, StopReason.ERROR, e.getMessage(), mapper.content());
            return;
        } catch (TerminalPushedException e) {
            return;
        }
        if (!mapper.terminalHandled()) {
            if (cancellation.isCancelled()) {
                fail(stream, StopReason.ABORTED, "cancelled before terminal response", mapper.content());
            } else {
                fail(stream, StopReason.ERROR, "stream ended before terminal response", mapper.content());
            }
        }
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

    private static void fail(AssistantMessageStream stream, StopReason reason, String message, List<Content> content) {
        var error = new Message.Assistant(List.copyOf(content), reason, message, Usage.zero(), Instant.now());
        stream.push(new AssistantMessageEvent.Error(reason, error));
    }

    @Override
    public void close() {
        executor.shutdown();
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
