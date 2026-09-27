package site.pplee.jcode.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import site.pplee.jcode.app.ApprovalSettings;
import site.pplee.jcode.app.ApiException;
import site.pplee.jcode.app.ManagedSession;
import site.pplee.jcode.app.SessionRegistry;
import site.pplee.jcode.codingagent.CodingAgentSessionOptions;
import site.pplee.jcode.codingagent.InputDeliveryMode;
import site.pplee.jcode.protocol.ApiError;
import site.pplee.jcode.protocol.ErrorCode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Independently running loopback HTTP host for one managed data directory. */
public final class JcodeServer implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(JcodeServer.class.getName());

    private final ServerConfig config;
    private final SessionRegistry sessions;
    private final ServerInstanceFiles instance;
    private final HttpServer http;
    private final ExecutorService requests;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final HttpApi api;
    private final Consumer<HttpExchange> beforeStopResponse;
    private final UnaryOperator<CodingAgentSessionOptions> optionsDecorator;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CountDownLatch terminated = new CountDownLatch(1);

    private JcodeServer(ServerConfig config, SessionRegistry sessions,
            ServerInstanceFiles instance, HttpServer http,
            ExecutorService requests, ObjectMapper mapper,
            Consumer<HttpExchange> beforeStopResponse,
            UnaryOperator<CodingAgentSessionOptions> optionsDecorator) {
        this.config = config;
        this.sessions = sessions;
        this.instance = instance;
        this.http = http;
        this.requests = requests;
        this.mapper = mapper;
        this.beforeStopResponse = beforeStopResponse;
        this.optionsDecorator = optionsDecorator;
        this.endpoint = URI.create("http://127.0.0.1:" + http.getAddress().getPort());
        this.api = new HttpApi(this, config, sessions, mapper);
    }

    public static JcodeServer start(ServerConfig config) throws IOException {
        return start(config, new SessionRegistry());
    }

    /** Testable host entry with an explicitly owned application registry. */
    static JcodeServer start(ServerConfig config, SessionRegistry sessions) throws IOException {
        return start(config, sessions, exchange -> { });
    }

    /** Package-private response boundary for deterministic disconnect tests. */
    static JcodeServer start(ServerConfig config, SessionRegistry sessions,
            Consumer<HttpExchange> beforeStopResponse) throws IOException {
        return start(config, sessions, beforeStopResponse, UnaryOperator.identity());
    }

    /** Package-private assembly boundary for controlled HTTP integration tests. */
    static JcodeServer start(ServerConfig config, SessionRegistry sessions,
            Consumer<HttpExchange> beforeStopResponse,
            UnaryOperator<CodingAgentSessionOptions> optionsDecorator) throws IOException {
        Objects.requireNonNull(config);
        Objects.requireNonNull(sessions);
        Objects.requireNonNull(beforeStopResponse);
        Objects.requireNonNull(optionsDecorator);
        var mapper = new ObjectMapper();
        var instance = ServerInstanceFiles.acquire(config.dataDirectory(), mapper);
        HttpServer http = null;
        ExecutorService requests = null;
        try {
            http = HttpServer.create(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), config.port()), 0);
            requests = Executors.newVirtualThreadPerTaskExecutor();
            http.setExecutor(requests);
            var server = new JcodeServer(config, sessions, instance, http, requests, mapper,
                    beforeStopResponse, optionsDecorator);
            http.createContext("/", server::handle);
            http.start();
            instance.publish(server.endpoint.toString());
            return server;
        } catch (IOException | RuntimeException | Error failure) {
            if (http != null) {
                try {
                    http.stop(0);
                } catch (RuntimeException stopFailure) {
                    failure.addSuppressed(stopFailure);
                }
            }
            if (requests != null) {
                try {
                    requests.shutdownNow();
                } catch (RuntimeException executorFailure) {
                    failure.addSuppressed(executorFailure);
                }
            }
            try {
                instance.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public URI endpoint() {
        return endpoint;
    }

    public String instanceId() {
        return instance.instanceId();
    }

    public Path tokenFile() {
        return instance.tokenFile();
    }

    SessionRegistry sessions() {
        return sessions;
    }

    CodingAgentSessionOptions optionsForWorkspace(String workspaceId) {
        Path workspace = config.workspaces().get(workspaceId);
        if (workspace == null) {
            throw new IllegalArgumentException("unknown workspaceId");
        }
        var options = CodingAgentSessionOptions.builder(workspace)
                .userConfigDirectory(config.userConfigDirectory())
                .systemEnvironment()
                .inputDeliveryMode(InputDeliveryMode.RUN_SCOPED)
                .build();
        var decorated = Objects.requireNonNull(optionsDecorator.apply(options));
        if (decorated.inputDeliveryMode() != InputDeliveryMode.RUN_SCOPED
                || !decorated.workingDirectory().equals(workspace)) {
            throw new IllegalArgumentException("server options must keep the strict workspace");
        }
        return decorated;
    }

    Path sessionDirectory(String workspaceId) {
        optionsForWorkspace(workspaceId);
        return config.dataDirectory().resolve("sessions").resolve(workspaceId);
    }

    ApprovalSettings approvalSettings() {
        return new ApprovalSettings(config.approvalTools(), config.approvalTimeout());
    }

    /** Keep the main process alive independently of client connections. */
    public void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    /** Signal shutdown cancels active work and uses one total wait window. */
    public void shutdown(Duration wait) {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        sessions.beginShutdown();
        long deadline = System.nanoTime() + wait.toNanos();
        var managed = sessions.managedSessions();
        for (var session : managed) {
            for (var run : session.snapshot().runs()) {
                if (!run.status().terminal()) {
                    try {
                        session.cancel(run.runId());
                    } catch (RuntimeException failure) {
                        LOGGER.log(Level.WARNING, "run cancellation failed during shutdown", failure);
                    }
                }
            }
        }
        for (var session : managed) {
            waitForRunSettlement(session, deadline);
        }
        closeSessions(managed, deadline);
        try {
            http.stop(0);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "HTTP stop failed during shutdown", failure);
        }
        try {
            requests.shutdownNow();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "request executor stop failed", failure);
        }
        try {
            instance.close();
        } catch (IOException failure) {
            LOGGER.log(Level.WARNING, "instance cleanup failed during shutdown", failure);
        } finally {
            terminated.countDown();
        }
    }

    @Override
    public void close() {
        shutdown(Duration.ofSeconds(10));
    }

    private void waitForRunSettlement(ManagedSession session, long deadline) {
        for (var run : session.snapshot().runs()) {
            if (run.status().terminal()) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            try {
                session.settled(run.runId()).toCompletableFuture()
                        .get(remaining, TimeUnit.NANOSECONDS);
            } catch (Exception failure) {
                LOGGER.log(Level.WARNING, "run did not settle before shutdown deadline", failure);
            }
        }
    }

    private void closeSessions(List<ManagedSession> managed, long deadline) {
        var cleanup = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var tasks = managed.stream().map(session -> CompletableFuture.runAsync(() -> {
                try {
                    sessions.close(session.sessionId());
                } catch (RuntimeException failure) {
                    LOGGER.log(Level.WARNING, "session close failed during shutdown", failure);
                }
            }, cleanup)).toList();
            for (var task : tasks) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    LOGGER.warning("session cleanup exceeded the shutdown deadline");
                    return;
                }
                try {
                    task.get(remaining, TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.TimeoutException timeout) {
                    LOGGER.warning("session cleanup exceeded the shutdown deadline");
                    return;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.util.concurrent.ExecutionException failure) {
                    LOGGER.log(Level.WARNING, "session cleanup failed", failure);
                }
            }
        } finally {
            cleanup.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        boolean stopAccepted = false;
        try (exchange) {
            exchange.getResponseHeaders().set("X-Request-Id", UUID.randomUUID().toString());
            String rawPath = exchange.getRequestURI().getRawPath();
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.equals(endpoint.toString())
                    && !config.allowedOrigins().contains(origin)) {
                json(exchange, 403, new ApiError(ErrorCode.ORIGIN_FORBIDDEN,
                        "origin is not allowed"));
                return;
            }
            if (origin != null) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
                exchange.getResponseHeaders().set("Vary", "Origin");
            }
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                preflight(exchange, rawPath);
                return;
            }
            if (!authorized(exchange)) {
                json(exchange, 401, new ApiError(ErrorCode.UNAUTHORIZED,
                        "service token is required"));
                return;
            }
            if (closing.get()) {
                json(exchange, 503, new ApiError(ErrorCode.SERVER_STOPPING,
                        "server is stopping"));
                return;
            }
            ApiRoutes.Route route;
            try {
                route = ApiRoutes.match(rawPath);
            } catch (IllegalArgumentException failure) {
                json(exchange, 400, new ApiError(ErrorCode.INVALID_ARGUMENT,
                        "path contains an invalid identifier"));
                return;
            }
            if (route == null) {
                json(exchange, 404, new ApiError(ErrorCode.NOT_FOUND, "route was not found"));
                return;
            }
            if (!route.methods().contains(exchange.getRequestMethod())) {
                methodNotAllowed(exchange, route.allowHeader());
                return;
            }
            if (route.kind() == ApiRoutes.Kind.CAPABILITIES) {
                json(exchange, 200, new Capabilities(instance.instanceId(),
                        endpoint.toString(), 1, 1,
                        List.of("sessions", "runs", "inputs", "approvals", "history", "sse", "idleStop"),
                        Map.of("managedSessions", SessionRegistry.MAX_MANAGED_SESSIONS,
                                "jsonBodyBytes", HttpApi.MAX_BODY_BYTES,
                                "subscriptionsPerSession", 32)));
                return;
            }
            if (route.kind() == ApiRoutes.Kind.STOP) {
                if (!sessions.beginShutdownIfIdle()) {
                    json(exchange, 409, new ApiError(ErrorCode.SESSION_BUSY,
                            "server has active work or is already stopping"));
                    return;
                }
                stopAccepted = true;
                beforeStopResponse.accept(exchange);
                json(exchange, 202, new StopAccepted("stopping"));
                return;
            }
            HttpApi.Response response;
            try {
                response = api.dispatch(exchange, route);
            } catch (ApiException rejection) {
                json(exchange, status(rejection.error().code()), rejection.error());
                return;
            } catch (IllegalArgumentException invalid) {
                json(exchange, 400, new ApiError(ErrorCode.INVALID_ARGUMENT,
                        "request is invalid"));
                return;
            } catch (RuntimeException failure) {
                LOGGER.warning("HTTP request failed: " + failure.getClass().getSimpleName());
                if (exchange.getResponseCode() < 0) {
                    json(exchange, 500, new ApiError(ErrorCode.INTERNAL_ERROR,
                            "request could not be completed"));
                    return;
                }
                throw failure;
            }
            if (response != null) {
                if (response.status() == 204) {
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    json(exchange, response.status(), response.body());
                }
            }
        } finally {
            if (stopAccepted) {
                Thread.startVirtualThread(this::close);
            }
        }
    }

    private void preflight(HttpExchange exchange, String rawPath) throws IOException {
        if (exchange.getRequestHeaders().getFirst("Origin") == null) {
            json(exchange, 403, new ApiError(ErrorCode.ORIGIN_FORBIDDEN,
                    "preflight origin is required"));
            return;
        }
        ApiRoutes.Route route;
        try {
            route = ApiRoutes.match(rawPath);
        } catch (IllegalArgumentException invalid) {
            json(exchange, 400, new ApiError(ErrorCode.INVALID_ARGUMENT,
                    "path contains an invalid identifier"));
            return;
        }
        if (route == null) {
            json(exchange, 404, new ApiError(ErrorCode.NOT_FOUND, "route was not found"));
            return;
        }
        String requestedMethod = exchange.getRequestHeaders()
                .getFirst("Access-Control-Request-Method");
        String requestedHeaders = exchange.getRequestHeaders()
                .getFirst("Access-Control-Request-Headers");
        if (!route.methods().contains(requestedMethod)
                || requestedHeaders == null || requestedHeaders.isBlank()
                || java.util.Arrays.stream(requestedHeaders.toLowerCase(Locale.ROOT)
                                .split(",", -1))
                        .map(String::strip)
                        .anyMatch(header -> !header.equals("authorization")
                                && !header.equals("content-type")
                                && !(route.kind() == ApiRoutes.Kind.EVENTS
                                        && header.equals("last-event-id")))) {
            json(exchange, 403, new ApiError(ErrorCode.INVALID_ARGUMENT,
                    "preflight is not allowed"));
            return;
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", route.allowHeader());
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                route.kind() == ApiRoutes.Kind.EVENTS
                        ? "Authorization, Content-Type, Last-Event-ID"
                        : "Authorization, Content-Type");
        exchange.sendResponseHeaders(204, -1);
    }

    private boolean authorized(HttpExchange exchange) {
        var values = exchange.getRequestHeaders().get("Authorization");
        if (values == null || values.size() != 1
                || !values.getFirst().startsWith("Bearer ")) {
            return false;
        }
        byte[] provided = values.getFirst().substring(7).getBytes(StandardCharsets.UTF_8);
        byte[] expected = instance.token().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, expected);
    }

    private void methodNotAllowed(HttpExchange exchange, String method) throws IOException {
        exchange.getResponseHeaders().set("Allow", method);
        json(exchange, 405, new ApiError(ErrorCode.METHOD_NOT_ALLOWED,
                "method is not supported"));
    }

    private void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = mapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static int status(ErrorCode code) {
        return switch (code) {
            case UNAUTHORIZED -> 401;
            case ORIGIN_FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case METHOD_NOT_ALLOWED -> 405;
            case PAYLOAD_TOO_LARGE -> 413;
            case UNSUPPORTED_MEDIA_TYPE -> 415;
            case CAPACITY_EXCEEDED -> 429;
            case SERVER_STOPPING -> 503;
            case INTERNAL_ERROR -> 500;
            case INVALID_ARGUMENT -> 400;
            default -> 409;
        };
    }

    private record Capabilities(String instanceId, String endpoint,
            int httpMajorVersion, int eventSchemaVersion, List<String> operations,
            Map<String, Integer> limits) { }

    private record StopAccepted(String status) { }
}
