package site.pplee.jcode.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import site.pplee.jcode.app.ApprovalSettings;
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
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CountDownLatch terminated = new CountDownLatch(1);

    private JcodeServer(ServerConfig config, SessionRegistry sessions,
            ServerInstanceFiles instance, HttpServer http,
            ExecutorService requests, ObjectMapper mapper) {
        this.config = config;
        this.sessions = sessions;
        this.instance = instance;
        this.http = http;
        this.requests = requests;
        this.mapper = mapper;
        this.endpoint = URI.create("http://127.0.0.1:" + http.getAddress().getPort());
    }

    public static JcodeServer start(ServerConfig config) throws IOException {
        return start(config, new SessionRegistry());
    }

    /** Testable host entry with an explicitly owned application registry. */
    static JcodeServer start(ServerConfig config, SessionRegistry sessions) throws IOException {
        Objects.requireNonNull(config);
        Objects.requireNonNull(sessions);
        var mapper = new ObjectMapper();
        var instance = ServerInstanceFiles.acquire(config.dataDirectory(), mapper);
        HttpServer http = null;
        ExecutorService requests = null;
        try {
            http = HttpServer.create(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), config.port()), 0);
            requests = Executors.newVirtualThreadPerTaskExecutor();
            http.setExecutor(requests);
            var server = new JcodeServer(config, sessions, instance, http, requests, mapper);
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
        return CodingAgentSessionOptions.builder(workspace)
                .userConfigDirectory(config.userConfigDirectory())
                .systemEnvironment()
                .inputDeliveryMode(InputDeliveryMode.RUN_SCOPED)
                .build();
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
            String path = exchange.getRequestURI().getPath();
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
                preflight(exchange, path);
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
            if ("/v1/capabilities".equals(path)) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "GET");
                    return;
                }
                json(exchange, 200, new Capabilities(instance.instanceId(),
                        endpoint.toString(), 1, 1, List.of("capabilities", "idleStop")));
                return;
            }
            if ("/v1/server/stop".equals(path)) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "POST");
                    return;
                }
                if (!sessions.beginShutdownIfIdle()) {
                    json(exchange, 409, new ApiError(ErrorCode.SESSION_BUSY,
                            "server has active work or is already stopping"));
                    return;
                }
                json(exchange, 202, new StopAccepted("stopping"));
                stopAccepted = true;
                return;
            }
            json(exchange, 404, new ApiError(ErrorCode.NOT_FOUND, "route was not found"));
        } finally {
            if (stopAccepted) {
                Thread.startVirtualThread(this::close);
            }
        }
    }

    private void preflight(HttpExchange exchange, String path) throws IOException {
        if (exchange.getRequestHeaders().getFirst("Origin") == null) {
            json(exchange, 403, new ApiError(ErrorCode.ORIGIN_FORBIDDEN,
                    "preflight origin is required"));
            return;
        }
        String allowedMethod = switch (path) {
            case "/v1/capabilities" -> "GET";
            case "/v1/server/stop" -> "POST";
            default -> null;
        };
        if (allowedMethod == null) {
            json(exchange, 404, new ApiError(ErrorCode.NOT_FOUND, "route was not found"));
            return;
        }
        String requestedMethod = exchange.getRequestHeaders()
                .getFirst("Access-Control-Request-Method");
        String requestedHeaders = exchange.getRequestHeaders()
                .getFirst("Access-Control-Request-Headers");
        if (!allowedMethod.equals(requestedMethod)
                || requestedHeaders == null
                || !List.of(requestedHeaders.toLowerCase(Locale.ROOT).split(",\\s*"))
                        .stream().allMatch(header -> header.equals("authorization")
                                || header.equals("content-type"))) {
            json(exchange, 403, new ApiError(ErrorCode.INVALID_ARGUMENT,
                    "preflight is not allowed"));
            return;
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", allowedMethod);
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                "Authorization, Content-Type");
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

    private record Capabilities(String instanceId, String endpoint,
            int httpMajorVersion, int eventSchemaVersion, List<String> operations) { }

    private record StopAccepted(String status) { }
}
