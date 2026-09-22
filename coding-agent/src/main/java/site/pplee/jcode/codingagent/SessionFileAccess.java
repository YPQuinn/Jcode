package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.session.SessionEntry;
import site.pplee.jcode.codingagent.session.SessionFileLockException;
import site.pplee.jcode.codingagent.session.SessionHeader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Coordinates product-owned in-process channel lifetimes for session files. */
final class SessionFileAccess {
    private static final Object REGISTRY_LOCK = new Object();
    private static final List<WriterRegistration> WRITERS = new ArrayList<>();
    private static final List<ReaderRegistration> READERS = new ArrayList<>();

    private SessionFileAccess() {
    }

    /**
     * Reserve a writer before opening its channel. Existing writers are rejected;
     * an already-started temporary read is allowed to close before this returns.
     */
    static WriterRegistration reserveWriter(Path path, boolean expectsDiscoverySource)
            throws IOException {
        var identity = FileIdentity.resolve(path);
        var registration = new WriterRegistration(identity, expectsDiscoverySource);
        synchronized (REGISTRY_LOCK) {
            if (findWriter(identity) != null) {
                throw new SessionFileLockException(identity.path(), null);
            }
            WRITERS.add(registration);
            try {
                while (hasReader(identity)) {
                    REGISTRY_LOCK.wait();
                }
            } catch (InterruptedException failure) {
                WRITERS.remove(registration);
                registration.terminate();
                REGISTRY_LOCK.notifyAll();
                Thread.currentThread().interrupt();
                throw new IOException(
                        "interrupted while waiting to open session writer: " + identity.path(),
                        failure);
            }
        }
        return registration;
    }

    /** Publish a fully initialized file owner. */
    static void activate(WriterRegistration registration, SessionFile owner) {
        var currentIdentity = FileIdentity.resolve(owner.path());
        synchronized (REGISTRY_LOCK) {
            requireRegistered(registration);
            registration.activate(owner, currentIdentity);
        }
    }

    /** Supply the Manager-backed, in-memory discovery view for an active owner. */
    static void attachDiscoverySource(
            WriterRegistration registration,
            DiscoverySource discoverySource
    ) {
        synchronized (REGISTRY_LOCK) {
            requireRegistered(registration);
        }
        registration.attachDiscoverySource(discoverySource);
    }

    /** Release a failed reservation after its channel has been closed. */
    static void releaseFailed(WriterRegistration registration) {
        synchronized (REGISTRY_LOCK) {
            WRITERS.remove(registration);
            REGISTRY_LOCK.notifyAll();
        }
        registration.terminate();
    }

    /** Close an owner while its reservation still prevents another channel open. */
    static void close(WriterRegistration registration) throws IOException {
        synchronized (REGISTRY_LOCK) {
            requireRegistered(registration);
        }
        registration.beginClosing();

        IOException failure = null;
        try {
            registration.owner().closeOwnedResources();
        } catch (IOException closeFailure) {
            failure = closeFailure;
        } finally {
            synchronized (REGISTRY_LOCK) {
                WRITERS.remove(registration);
                REGISTRY_LOCK.notifyAll();
            }
            registration.terminate();
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Read an active session from its Manager-owned history. For an inactive
     * file, reserve a same-file reader slot while a temporary channel is open.
     */
    static DiscoveryResult read(Path path) throws IOException {
        var identity = FileIdentity.resolve(path);
        WriterRegistration writer;
        ReaderRegistration reader = null;
        synchronized (REGISTRY_LOCK) {
            writer = findWriter(identity);
            if (writer == null) {
                reader = new ReaderRegistration(identity);
                READERS.add(reader);
            }
        }

        if (writer != null) {
            return writer.readForDiscovery();
        }

        try (var channel = FileChannel.open(identity.path(), StandardOpenOption.READ)) {
            var loaded = new SessionFileReader(identity.path(), new SessionCodec()).read(channel);
            return new DiscoveryResult(loaded.header(), loaded.entries(), loaded.recovery());
        } finally {
            synchronized (REGISTRY_LOCK) {
                READERS.remove(reader);
                REGISTRY_LOCK.notifyAll();
            }
        }
    }

    private static WriterRegistration findWriter(FileIdentity identity) {
        for (var registration : WRITERS) {
            if (registration.identity.matches(identity)) {
                return registration;
            }
        }
        return null;
    }

    private static boolean hasReader(FileIdentity identity) {
        for (var registration : READERS) {
            if (registration.identity.matches(identity)) {
                return true;
            }
        }
        return false;
    }

    private static void requireRegistered(WriterRegistration registration) {
        if (!WRITERS.contains(registration)) {
            throw new IllegalStateException("unknown session writer registration");
        }
    }

    record DiscoveryResult(
            SessionHeader header,
            List<SessionEntry> entries,
            SessionFileReader.TailRecovery recovery
    ) {
        DiscoveryResult {
            Objects.requireNonNull(header, "header must not be null");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        }
    }

    @FunctionalInterface
    interface DiscoverySource {
        DiscoveryResult read() throws IOException;
    }

    static final class WriterRegistration {
        private FileIdentity identity;
        private final boolean expectsDiscoverySource;
        private SessionFile owner;
        private DiscoverySource discoverySource;
        private boolean closing;
        private boolean terminated;

        private WriterRegistration(FileIdentity identity, boolean expectsDiscoverySource) {
            this.identity = identity;
            this.expectsDiscoverySource = expectsDiscoverySource;
        }

        private synchronized void activate(SessionFile value, FileIdentity currentIdentity) {
            if (owner != null) {
                throw new IllegalStateException("session writer registration is already active");
            }
            if (terminated) {
                throw new IllegalStateException("session writer registration is terminated");
            }
            owner = Objects.requireNonNull(value, "owner must not be null");
            identity = Objects.requireNonNull(
                    currentIdentity, "currentIdentity must not be null");
            notifyAll();
        }

        private synchronized void attachDiscoverySource(DiscoverySource value) {
            if (discoverySource != null) {
                throw new IllegalStateException("session discovery source is already attached");
            }
            if (terminated || closing) {
                throw new IllegalStateException("session writer registration is closing");
            }
            discoverySource = Objects.requireNonNull(
                    value, "discoverySource must not be null");
            notifyAll();
        }

        private DiscoveryResult readForDiscovery() throws IOException {
            DiscoverySource source;
            synchronized (this) {
                while (owner == null && !terminated) {
                    awaitInitialization();
                }
                while (expectsDiscoverySource
                        && discoverySource == null
                        && !closing
                        && !terminated) {
                    awaitInitialization();
                }
                if (terminated) {
                    throw new IOException(
                            "session writer closed during discovery: " + identity.path());
                }
                if (closing) {
                    throw new IOException("session writer is closing: " + identity.path());
                }
                source = discoverySource;
                if (source == null) {
                    throw new IOException(
                            "active session writer has no discovery source: " + identity.path());
                }
            }
            return source.read();
        }

        private synchronized SessionFile owner() {
            if (owner == null) {
                throw new IllegalStateException("session writer owner is not active");
            }
            return owner;
        }

        private synchronized void beginClosing() {
            closing = true;
            notifyAll();
        }

        private synchronized void terminate() {
            terminated = true;
            notifyAll();
        }

        private void awaitInitialization() throws IOException {
            try {
                wait();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(
                        "interrupted while waiting for session writer setup: " + identity.path(),
                        failure);
            }
        }
    }

    private record ReaderRegistration(FileIdentity identity) {
    }

    private record FileIdentity(Path path, Object fileKey) {
        private static FileIdentity resolve(Path source) {
            var normalized = Objects.requireNonNull(source, "path must not be null")
                    .toAbsolutePath()
                    .normalize();
            Path resolved = normalized;
            Object key = null;
            try {
                resolved = normalized.toRealPath();
                key = Files.readAttributes(resolved, BasicFileAttributes.class).fileKey();
            } catch (IOException | SecurityException ignored) {
                var parent = normalized.getParent();
                if (parent != null) {
                    try {
                        resolved = parent.toRealPath()
                                .resolve(normalized.getFileName())
                                .normalize();
                    } catch (IOException | SecurityException ignoredParent) {
                        // The normalized path remains the best available identity.
                    }
                }
            }
            return new FileIdentity(resolved, key);
        }

        private boolean matches(FileIdentity other) {
            return path.equals(other.path)
                    || fileKey != null && other.fileKey != null && fileKey.equals(other.fileKey);
        }
    }
}
