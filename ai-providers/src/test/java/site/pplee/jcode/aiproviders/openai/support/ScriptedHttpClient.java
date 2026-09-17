package site.pplee.jcode.aiproviders.openai.support;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test {@link HttpClient} that returns scripted {@link InputStream} bodies.
 * Records whether a previously tracked body was already closed when the next
 * {@code sendAsync} starts.
 */
public final class ScriptedHttpClient extends HttpClient {
    private static final URI ENDPOINT = URI.create("http://127.0.0.1/v1/responses");

    private final ConcurrentLinkedQueue<HttpResponse<InputStream>> queue = new ConcurrentLinkedQueue<>();
    private final List<CloseTrackingInputStream> tracked = new CopyOnWriteArrayList<>();
    private final AtomicInteger sendCount = new AtomicInteger();
    private volatile Boolean previousTrackedClosedBeforeSend;

    public void enqueue(int status, Map<String, String> headers, InputStream body) {
        if (body instanceof CloseTrackingInputStream trackedBody) {
            tracked.add(trackedBody);
        }
        queue.add(new FakeResponse(status, headersOf(headers), body));
    }

    public int sendCount() {
        return sendCount.get();
    }

    public Boolean previousTrackedClosedBeforeSend() {
        return previousTrackedClosedBeforeSend;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        int n = sendCount.incrementAndGet();
        if (n > 1 && !tracked.isEmpty()) {
            previousTrackedClosedBeforeSend = tracked.get(0).isClosed();
        }
        HttpResponse<InputStream> next = queue.poll();
        if (next == null) {
            return CompletableFuture.failedFuture(new IOException("no scripted response"));
        }
        @SuppressWarnings("unchecked")
        HttpResponse<T> cast = (HttpResponse<T>) next;
        return CompletableFuture.completedFuture(cast);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler
    ) {
        return sendAsync(request, handler);
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    private static HttpHeaders headersOf(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
        }
        Map<String, List<String>> mapped = headers.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue())));
        return HttpHeaders.of(mapped, (a, b) -> true);
    }

    private record FakeResponse(int status, HttpHeaders headers, InputStream body)
            implements HttpResponse<InputStream> {
        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(ENDPOINT).POST(HttpRequest.BodyPublishers.noBody()).build();
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public InputStream body() {
            return body;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return ENDPOINT;
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }
}
