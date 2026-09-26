package site.pplee.jcode.app;

import site.pplee.jcode.codingagent.CodingAgentConfig;
import site.pplee.jcode.codingagent.CodingAgentSession;
import site.pplee.jcode.codingagent.InputDeliveryMode;
import site.pplee.jcode.protocol.ErrorCode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Owns managed sessions and opens each historical file at most once per process. */
public final class SessionRegistry {
    public static final int MAX_MANAGED_SESSIONS = 64;

    private final Object lock = new Object();
    private final Map<String, ManagedSession> byId = new LinkedHashMap<>();
    private final Map<Path, CompletableFuture<ManagedSession>> byFile = new LinkedHashMap<>();
    private final Map<String, Path> pathById = new LinkedHashMap<>();

    /** Create a file-backed strict Session and register its long-lived writer. */
    public ManagedSession create(CodingAgentConfig config, Path sessionDirectory) throws IOException {
        requireStrict(config);
        Objects.requireNonNull(sessionDirectory, "sessionDirectory must not be null");
        synchronized (lock) {
            requireCapacity();
        }
        var core = CodingAgentSession.create(config, sessionDirectory);
        var managed = new ManagedSession(core);
        try {
            Path file = core.sessionFile().orElseThrow().toRealPath();
            synchronized (lock) {
                requireCapacity();
                byId.put(managed.sessionId(), managed);
                byFile.put(file, CompletableFuture.completedFuture(managed));
                pathById.put(managed.sessionId(), file);
            }
            return managed;
        } catch (IOException | RuntimeException failure) {
            try {
                core.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** Reuse an existing owner or open a file once even under concurrent callers. */
    public ManagedSession open(CodingAgentConfig config, Path sessionFile) throws IOException {
        requireStrict(config);
        Path file = Objects.requireNonNull(sessionFile, "sessionFile must not be null").toRealPath();
        CompletableFuture<ManagedSession> opening;
        boolean owner;
        synchronized (lock) {
            opening = byFile.get(file);
            owner = opening == null;
            if (owner) {
                requireCapacity();
                opening = new CompletableFuture<>();
                byFile.put(file, opening);
            }
        }
        if (!owner) {
            try {
                var managed = opening.join();
                if (!managed.workingDirectory().equals(config.workingDirectory())) {
                    throw new ApiException(ErrorCode.STATE_CONFLICT,
                            "managed session uses a different working directory");
                }
                return managed;
            } catch (CompletionException failure) {
                if (failure.getCause() instanceof IOException io) {
                    throw io;
                }
                if (failure.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw failure;
            }
        }

        CodingAgentSession core = null;
        try {
            core = CodingAgentSession.open(config, file);
            var managed = new ManagedSession(core);
            synchronized (lock) {
                var existing = byId.putIfAbsent(managed.sessionId(), managed);
                if (existing != null) {
                    throw new ApiException(ErrorCode.STATE_CONFLICT, "session identity is already managed");
                }
                pathById.put(managed.sessionId(), file);
            }
            opening.complete(managed);
            return managed;
        } catch (IOException | RuntimeException | Error failure) {
            if (core != null) {
                try {
                    core.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            synchronized (lock) {
                byFile.remove(file, opening);
            }
            opening.completeExceptionally(failure);
            throw failure;
        }
    }

    public Optional<ManagedSession> find(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        synchronized (lock) {
            return Optional.ofNullable(byId.get(sessionId));
        }
    }

    /** Close only an idle session, then release its file ownership. */
    public void close(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        ManagedSession session;
        synchronized (lock) {
            session = byId.get(sessionId);
            if (session == null) {
                throw new ApiException(ErrorCode.NOT_FOUND, "session is not managed");
            }
        }
        session.closeIdle();
        synchronized (lock) {
            byId.remove(sessionId, session);
            var file = pathById.remove(sessionId);
            if (file != null) {
                byFile.remove(file);
            }
        }
    }

    private void requireCapacity() {
        if (byId.size() + byFile.values().stream().filter(future -> !future.isDone()).count()
                >= MAX_MANAGED_SESSIONS) {
            throw new ApiException(ErrorCode.CAPACITY_EXCEEDED, "managed session limit reached");
        }
    }

    private static void requireStrict(CodingAgentConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        if (config.inputDeliveryMode() != InputDeliveryMode.RUN_SCOPED) {
            throw new IllegalArgumentException("managed sessions require RUN_SCOPED input delivery");
        }
    }
}
