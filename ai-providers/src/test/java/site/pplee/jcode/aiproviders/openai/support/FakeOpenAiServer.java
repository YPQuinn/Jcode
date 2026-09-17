package site.pplee.jcode.aiproviders.openai.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    private enum Kind {
        HTTP,
        DROP
    }

    private record Script(Kind kind, int status, String body, Map<String, String> headers) {
    }

    private final HttpServer server;
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Script> queue = new ConcurrentLinkedQueue<>();
    private final Object requestLock = new Object();
    private final CountDownLatch requestReceived = new CountDownLatch(1);
    private final CountDownLatch headersSent = new CountDownLatch(1);
    private final CountDownLatch bodyClosed = new CountDownLatch(1);
    private volatile List<Chunk> chunks = List.of();
    private volatile int statusCode = 200;
    private volatile String errorBody = "";
    private volatile Map<String, String> responseHeaders = Map.of();
    private volatile CountDownLatch holdBeforeHeaders;
    private volatile CountDownLatch holdBeforeErrorBody;
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
        respond(statusCode, body, Map.of());
    }

    /** Serve a single response body with explicit response headers. */
    public void respond(int statusCode, String body, Map<String, String> headers) {
        this.statusCode = statusCode;
        this.errorBody = body;
        this.chunks = List.of(new Chunk(body, null));
        this.responseHeaders = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** Serve gated SSE chunks (status 200, chunked transfer). */
    public void setChunks(List<Chunk> chunks) {
        this.statusCode = 200;
        this.chunks = List.copyOf(chunks);
    }

    /** Queue a one-shot HTTP response consumed before the {@link #respond} fallback. */
    public void enqueue(int statusCode, String body) {
        enqueue(statusCode, body, Map.of());
    }

    /** Queue a one-shot HTTP response with explicit headers. */
    public void enqueue(int statusCode, String body, Map<String, String> headers) {
        queue.add(new Script(
                Kind.HTTP,
                statusCode,
                body == null ? "" : body,
                headers == null ? Map.of() : Map.copyOf(headers)));
    }

    /**
     * Queue a connect/send failure: the request body is captured, then the
     * connection is closed without HTTP response headers.
     */
    public void enqueueTransportFailure() {
        queue.add(new Script(Kind.DROP, 0, "", Map.of()));
    }

    /** Wait until at least {@code count} requests have been captured. */
    public boolean awaitRequests(int count, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (requestLock) {
            while (requests.size() < count) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
                int nanos = (int) (remaining % 1_000_000L);
                requestLock.wait(Math.max(1L, millis), nanos);
            }
            return true;
        }
    }

    /**
     * Hold the handler after the request is captured and before response
     * headers are written. Tests must not release this latch to prove that
     * client cancellation aborts {@code sendAsync}.
     */
    public void holdBeforeHeaders(CountDownLatch gate) {
        this.holdBeforeHeaders = gate;
    }

    /**
     * After non-2xx headers are sent, hold before writing the error body so
     * tests can cancel or close while the client is blocked on the read.
     */
    public void holdBeforeErrorBody(CountDownLatch gate) {
        this.holdBeforeErrorBody = gate;
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
            synchronized (requestLock) {
                requestLock.notifyAll();
            }
            requestReceived.countDown();

            Script scripted = queue.poll();
            CountDownLatch beforeHeaders = holdBeforeHeaders;
            if (scripted != null) {
                if (scripted.kind() != Kind.DROP
                        && beforeHeaders != null
                        && !awaitGate(beforeHeaders)) {
                    return;
                }
                writeScripted(exchange, scripted);
                return;
            }
            if (beforeHeaders != null && !awaitGate(beforeHeaders)) {
                return;
            }

            writeFallback(exchange);
        } finally {
            bodyClosed.countDown();
        }
    }

    private void writeScripted(HttpExchange exchange, Script script) throws IOException {
        if (script.kind() == Kind.DROP) {
            exchange.close();
            return;
        }
        applyHeaders(exchange, script.headers());
        writeHttp(exchange, script.status(), script.body(), List.of());
    }

    private void writeFallback(HttpExchange exchange) throws IOException {
        applyHeaders(exchange, responseHeaders);
        writeHttp(exchange, statusCode, errorBody, chunks);
    }

    private void writeHttp(HttpExchange exchange, int status, String body, List<Chunk> sseChunks)
            throws IOException {
        if (status != 200) {
            byte[] error = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            CountDownLatch bodyGate = holdBeforeErrorBody;
            if (bodyGate != null) {
                exchange.sendResponseHeaders(status, 1_000_000);
                headersSent.countDown();
                if (!awaitGate(bodyGate)) {
                    return;
                }
                try (var os = exchange.getResponseBody()) {
                    os.write(error);
                    os.flush();
                }
                return;
            }
            exchange.sendResponseHeaders(status, error.length);
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
            if (sseChunks.isEmpty()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
                return;
            }
            for (Chunk chunk : sseChunks) {
                if (chunk.gate() != null && !awaitGate(chunk.gate())) {
                    return;
                }
                os.write(chunk.text().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }
    }

    private static void applyHeaders(HttpExchange exchange, Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        for (var entry : headers.entrySet()) {
            exchange.getResponseHeaders().set(entry.getKey(), entry.getValue());
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
