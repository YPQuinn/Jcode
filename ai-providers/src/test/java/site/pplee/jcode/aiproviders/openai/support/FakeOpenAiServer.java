package site.pplee.jcode.aiproviders.openai.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Local fake OpenAI Responses endpoint for tests. Captures requests and
 * serves scripted SSE bodies, optionally gated per chunk so tests can
 * control when the server emits data.
 */
public final class FakeOpenAiServer implements AutoCloseable {

    /** One write of the response body; the gate (when set) is awaited before writing. */
    public record Chunk(String text, CountDownLatch gate) {
    }

    /** A captured request: path, headers and raw body. */
    public record CapturedRequest(String path, Map<String, List<String>> headers, String body) {
        /** Case-insensitive header lookup. */
        public String header(String name) {
            for (var entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    List<String> values = entry.getValue();
                    return values.isEmpty() ? null : values.get(0);
                }
            }
            return null;
        }
    }

    private final HttpServer server;
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch requestReceived = new CountDownLatch(1);
    private final CountDownLatch headersSent = new CountDownLatch(1);
    private final CountDownLatch bodyClosed = new CountDownLatch(1);
    private volatile List<Chunk> chunks = List.of();
    private volatile int statusCode = 200;
    private volatile String errorBody = "";
    private volatile CountDownLatch holdBeforeHeaders;
    private volatile boolean closed;

    public FakeOpenAiServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("failed to start fake OpenAI server", e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    /** Base URL pointing at this server's {@code /v1} root. */
    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    /** Serve a single response body with the given status. */
    public void respond(int statusCode, String body) {
        this.statusCode = statusCode;
        this.errorBody = body;
        this.chunks = List.of(new Chunk(body, null));
    }

    /** Serve gated SSE chunks (status 200, chunked transfer). */
    public void setChunks(List<Chunk> chunks) {
        this.statusCode = 200;
        this.chunks = List.copyOf(chunks);
    }

    /**
     * Hold the handler after the request is captured and before response
     * headers are written. Tests must not release this latch to prove that
     * client cancellation aborts {@code sendAsync}.
     */
    public void holdBeforeHeaders(CountDownLatch gate) {
        this.holdBeforeHeaders = gate;
    }

    /** Signaled once the current request has been captured. */
    public CountDownLatch requestReceived() {
        return requestReceived;
    }

    /** Signaled once HTTP response headers have been sent. */
    public CountDownLatch headersSent() {
        return headersSent;
    }

    /** Signaled once the handler finishes and the response body is closed. */
    public CountDownLatch bodyClosed() {
        return bodyClosed;
    }

    /** Requests captured so far. */
    public List<CapturedRequest> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            var body = exchange.getRequestBody().readAllBytes();
            requests.add(new CapturedRequest(
                    exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders()),
                    new String(body, StandardCharsets.UTF_8)));
            requestReceived.countDown();

            CountDownLatch beforeHeaders = holdBeforeHeaders;
            if (beforeHeaders != null && !awaitGate(beforeHeaders)) {
                return;
            }

            if (statusCode != 200) {
                byte[] error = errorBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(statusCode, error.length);
                headersSent.countDown();
                try (var os = exchange.getResponseBody()) {
                    os.write(error);
                }
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            headersSent.countDown();
            try (var os = exchange.getResponseBody()) {
                for (Chunk chunk : chunks) {
                    if (chunk.gate() != null && !awaitGate(chunk.gate())) {
                        return;
                    }
                    os.write(chunk.text().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }
        } finally {
            bodyClosed.countDown();
        }
    }

    private boolean awaitGate(CountDownLatch gate) {
        try {
            while (!closed) {
                if (gate.await(50, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        closed = true;
        server.stop(0);
    }
}
