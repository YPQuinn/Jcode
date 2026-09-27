package site.pplee.jcode.app;

import site.pplee.jcode.codingagent.CodingAgentConfig;
import site.pplee.jcode.codingagent.CodingAgentSession;
import site.pplee.jcode.codingagent.CodingAgentSessionFactory;
import site.pplee.jcode.codingagent.CodingAgentSessionOptions;
import site.pplee.jcode.codingagent.InputDeliveryMode;
import site.pplee.jcode.codingagent.SessionAssemblyException;
import site.pplee.jcode.protocol.ErrorCode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

/** Owns managed sessions and opens each historical file at most once per process. */
public final class SessionRegistry {
    public static final int MAX_MANAGED_SESSIONS = 64;

    private final Object lock = new Object();
    private final Object admissionLock = new Object();
    private final Map<String, ManagedSession> byId = new LinkedHashMap<>();
    private final Map<Path, CompletableFuture<ManagedSession>> byFile = new LinkedHashMap<>();
    private final Map<String, Path> pathById = new LinkedHashMap<>();
    private final Map<Path, RunOwner> workspaceRuns = new LinkedHashMap<>();
    private final Runnable beforeCloseCleanup;
    private final Runnable beforeRegistration;
    private final Runnable beforeOpenWait;
    private int pendingCreations;
    private volatile boolean stopping;

    public SessionRegistry() {
        this(() -> { }, () -> { });
    }

    /** Package-private handoff point for deterministic close/open interleaving tests. */
    SessionRegistry(Runnable beforeCloseCleanup) {
        this(beforeCloseCleanup, () -> { });
    }

    /** Package-private construction boundary for stop/register interleaving tests. */
    SessionRegistry(Runnable beforeCloseCleanup, Runnable beforeRegistration) {
        this(beforeCloseCleanup, beforeRegistration, () -> { });
    }

    /** Package-private observer after a caller selects an existing open attempt. */
    SessionRegistry(Runnable beforeCloseCleanup, Runnable beforeRegistration,
            Runnable beforeOpenWait) {
        this.beforeCloseCleanup = Objects.requireNonNull(beforeCloseCleanup);
        this.beforeRegistration = Objects.requireNonNull(beforeRegistration);
        this.beforeOpenWait = Objects.requireNonNull(beforeOpenWait);
    }

    /** Create a file-backed strict Session and register its long-lived writer. */
    public ManagedSession create(CodingAgentConfig config, Path sessionDirectory) throws IOException {
        return create(config, sessionDirectory, ApprovalSettings.none());
    }

    /** Create a managed Session with explicit per-tool approval settings. */
    public ManagedSession create(
            CodingAgentConfig config,
            Path sessionDirectory,
            ApprovalSettings approvals
    ) throws IOException {
        requireStrict(config);
        Objects.requireNonNull(sessionDirectory, "sessionDirectory must not be null");
        Objects.requireNonNull(approvals, "approvals must not be null");
        beginCreation();
        try {
            var holder = new AtomicReference<ManagedSession>();
            var core = CodingAgentSession.create(
                    ManagedSession.instrument(config, holder, approvals), sessionDirectory);
            return registerCreated(core, holder, approvals);
        } finally {
            endCreation();
        }
    }

    /** Create through settings-driven assembly while retaining application observation. */
    public ManagedSession create(
            CodingAgentSessionOptions options,
            Path sessionDirectory,
            ApprovalSettings approvals
    ) throws IOException, SessionAssemblyException {
        requireStrict(options);
        Objects.requireNonNull(sessionDirectory, "sessionDirectory must not be null");
        Objects.requireNonNull(approvals, "approvals must not be null");
        beginCreation();
        try {
            var holder = new AtomicReference<ManagedSession>();
            var core = CodingAgentSessionFactory.create(options, sessionDirectory,
                    config -> ManagedSession.instrument(config, holder, approvals)).session();
            return registerCreated(core, holder, approvals);
        } finally {
            endCreation();
        }
    }

    private ManagedSession registerCreated(
            CodingAgentSession core,
            AtomicReference<ManagedSession> holder,
            ApprovalSettings approvals
    ) throws IOException {
        try {
            var managed = new ManagedSession(core, approvals,
                    admissionLock, () -> stopping, this);
            holder.set(managed);
            Path file = core.sessionFile().orElseThrow().toRealPath();
            beforeRegistration.run();
            synchronized (lock) {
                requireAccepting();
                if (byId.putIfAbsent(managed.sessionId(), managed) != null) {
                    throw new ApiException(ErrorCode.STATE_CONFLICT,
                            "session identity is already managed");
                }
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
        return open(config, sessionFile, ApprovalSettings.none());
    }

    /** Reuse an owner or open with approval settings for newly loaded sessions. */
    public ManagedSession open(
            CodingAgentConfig config,
            Path sessionFile,
            ApprovalSettings approvals
    ) throws IOException {
        requireStrict(config);
        try {
            return openPrepared(config.workingDirectory(), sessionFile, approvals,
                    (file, holder) -> CodingAgentSession.open(
                            ManagedSession.instrument(config, holder, approvals), file));
        } catch (SessionAssemblyException assembly) {
            throw new IOException("settings-driven open of the same file failed", assembly);
        }
    }

    /** Open through settings-driven assembly without changing the file reuse contract. */
    public ManagedSession open(
            CodingAgentSessionOptions options,
            Path sessionFile,
            ApprovalSettings approvals
    ) throws IOException, SessionAssemblyException {
        requireStrict(options);
        return openPrepared(options.workingDirectory(), sessionFile, approvals,
                (file, holder) -> CodingAgentSessionFactory.open(options, file,
                        config -> ManagedSession.instrument(config, holder, approvals)).session());
    }

    private ManagedSession openPrepared(
            Path workingDirectory,
            Path sessionFile,
            ApprovalSettings approvals,
            CoreOpener opener
    ) throws IOException, SessionAssemblyException {
        Objects.requireNonNull(approvals, "approvals must not be null");
        Path file = Objects.requireNonNull(sessionFile, "sessionFile must not be null").toRealPath();
        CompletableFuture<ManagedSession> opening;
        boolean owner;
        synchronized (lock) {
            requireAccepting();
            opening = byFile.get(file);
            owner = opening == null;
            if (owner) {
                requireCapacity();
                opening = new CompletableFuture<>();
                byFile.put(file, opening);
            }
        }
        if (!owner) {
            beforeOpenWait.run();
            try {
                var managed = opening.join();
                if (managed.isClosed()) {
                    throw new ApiException(ErrorCode.SESSION_CLOSED, "session is closing");
                }
                if (!managed.workingDirectory().equals(workingDirectory)) {
                    throw new ApiException(ErrorCode.STATE_CONFLICT,
                            "managed session uses a different working directory");
                }
                return managed;
            } catch (CompletionException failure) {
                if (failure.getCause() instanceof IOException io) {
                    throw io;
                }
                if (failure.getCause() instanceof SessionAssemblyException assembly) {
                    throw assembly;
                }
                if (failure.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw failure;
            }
        }

        CodingAgentSession core = null;
        try {
            var holder = new AtomicReference<ManagedSession>();
            core = opener.open(file, holder);
            var managed = new ManagedSession(core, approvals,
                    admissionLock, () -> stopping, this);
            holder.set(managed);
            beforeRegistration.run();
            synchronized (lock) {
                requireAccepting();
                var existing = byId.putIfAbsent(managed.sessionId(), managed);
                if (existing != null) {
                    throw new ApiException(ErrorCode.STATE_CONFLICT, "session identity is already managed");
                }
                pathById.put(managed.sessionId(), file);
            }
            opening.complete(managed);
            return managed;
        } catch (IOException | SessionAssemblyException | RuntimeException | Error failure) {
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

    @FunctionalInterface
    private interface CoreOpener {
        CodingAgentSession open(Path file, AtomicReference<ManagedSession> holder)
                throws IOException, SessionAssemblyException;
    }

    public Optional<ManagedSession> find(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        synchronized (lock) {
            return Optional.ofNullable(byId.get(sessionId));
        }
    }

    /** Snapshot current owners without acquiring their lifecycle locks during I/O. */
    public List<ManagedSession> managedSessions() {
        synchronized (lock) {
            return List.copyOf(byId.values());
        }
    }

    /** Reserve one real workspace for the exact product Run being accepted. */
    void claimWorkspace(Path workspace, String sessionId, String runId) {
        synchronized (admissionLock) {
            var previous = workspaceRuns.putIfAbsent(workspace, new RunOwner(sessionId, runId));
            if (previous != null) {
                throw new ApiException(ErrorCode.SESSION_BUSY,
                        "another run is active in this workspace");
            }
        }
    }

    /** Release only the Run that acquired the workspace. */
    void releaseWorkspace(Path workspace, String sessionId, String runId) {
        synchronized (admissionLock) {
            workspaceRuns.remove(workspace, new RunOwner(sessionId, runId));
        }
    }

    /** Atomically reject new create/open work once every managed owner is idle. */
    public boolean beginShutdownIfIdle() {
        synchronized (admissionLock) {
            synchronized (lock) {
                if (stopping || pendingCreations != 0
                        || byFile.values().stream().anyMatch(future -> !future.isDone())
                        || byId.values().stream().anyMatch(session -> !session.isIdle())) {
                    return false;
                }
                stopping = true;
                return true;
            }
        }
    }

    /** Reject new file ownership while a process shutdown cancels active work. */
    public void beginShutdown() {
        synchronized (admissionLock) {
            synchronized (lock) {
                stopping = true;
            }
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
        try {
            session.closeIdle();
        } finally {
            if (session.isCloseSettled()) {
                beforeCloseCleanup.run();
                synchronized (lock) {
                    if (byId.remove(sessionId, session)) {
                        var file = pathById.remove(sessionId);
                        if (file != null) {
                            byFile.remove(file);
                        }
                    }
                }
            }
        }
    }

    private void requireCapacity() {
        if (byId.size() + pendingCreations
                + byFile.values().stream().filter(future -> !future.isDone()).count()
                >= MAX_MANAGED_SESSIONS) {
            throw new ApiException(ErrorCode.CAPACITY_EXCEEDED, "managed session limit reached");
        }
    }

    private void beginCreation() {
        synchronized (lock) {
            requireAccepting();
            requireCapacity();
            pendingCreations++;
        }
    }

    private void endCreation() {
        synchronized (lock) {
            pendingCreations--;
        }
    }

    private void requireAccepting() {
        if (stopping) {
            throw new ApiException(ErrorCode.SESSION_CLOSED, "session registry is stopping");
        }
    }

    private static void requireStrict(CodingAgentConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        if (config.inputDeliveryMode() != InputDeliveryMode.RUN_SCOPED) {
            throw new IllegalArgumentException("managed sessions require RUN_SCOPED input delivery");
        }
    }

    private static void requireStrict(CodingAgentSessionOptions options) {
        Objects.requireNonNull(options, "options must not be null");
        if (options.inputDeliveryMode() != InputDeliveryMode.RUN_SCOPED) {
            throw new IllegalArgumentException("managed sessions require RUN_SCOPED input delivery");
        }
    }

    private record RunOwner(String sessionId, String runId) { }
}
