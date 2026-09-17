package site.pplee.jcode.aiproviders.openai.support;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test {@link HttpClient} whose {@code sendAsync} throws a caller-supplied
 * runtime exception. Used to prove programming errors at the send boundary
 * are not retried and do not leak the exception message.
 */
public final class ThrowingSendHttpClient extends HttpClient {
    private final RuntimeException failure;
    private final AtomicInteger sendCount = new AtomicInteger();

    public ThrowingSendHttpClient(RuntimeException failure) {
        this.failure = failure;
    }

    public int sendCount() {
        return sendCount.get();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        sendCount.incrementAndGet();
        throw failure;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler
    ) {
        sendCount.incrementAndGet();
        throw failure;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        throw failure;
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
}
