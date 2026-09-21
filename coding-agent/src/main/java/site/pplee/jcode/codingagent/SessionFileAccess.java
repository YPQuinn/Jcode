package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.session.SessionFileLockException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Coordinates all in-process channel lifetimes for session files. */
final class SessionFileAccess {
    private static final List<WriterRegistration> WRITERS = new ArrayList<>();

    private SessionFileAccess() {
    }

    /** Reserve a path before opening a writer channel, rejecting aliases of an active writer. */
    static synchronized WriterRegistration reserveWriter(Path path)
            throws SessionFileLockException {
        var normalized = normalize(path);
        if (findWriter(normalized) != null) {
            throw new SessionFileLockException(normalized, null);
        }
        var registration = new WriterRegistration(normalized);
        WRITERS.add(registration);
        return registration;
    }

    /** Publish a fully initialized owner and wake readers waiting for writer setup. */
    static synchronized void activate(WriterRegistration registration, SessionFile owner) {
        requireRegistered(registration);
        if (registration.owner != null) {
            throw new IllegalStateException("session writer registration is already active");
        }
        registration.owner = owner;
        SessionFileAccess.class.notifyAll();
    }

    /** Release a failed reservation after its channel has been closed. */
    static synchronized void releaseFailed(WriterRegistration registration) {
        if (WRITERS.remove(registration)) {
            SessionFileAccess.class.notifyAll();
        }
    }

    /** Close an owner before exposing its path for another channel open. */
    static synchronized void close(WriterRegistration registration) throws IOException {
        requireRegistered(registration);
        IOException failure = null;
        try {
            registration.owner.closeOwnedResources();
        } catch (IOException closeFailure) {
            failure = closeFailure;
        } finally {
            WRITERS.remove(registration);
            SessionFileAccess.class.notifyAll();
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Read with the active owner's channel when present. Otherwise keep the
     * registry monitor for the complete temporary channel lifetime so a writer
     * cannot open until that channel has closed.
     */
    static synchronized SessionFileReader.ReadResult read(Path path) throws IOException {
        var normalized = normalize(path);
        WriterRegistration registration;
        while ((registration = findWriter(normalized)) != null && registration.owner == null) {
            try {
                SessionFileAccess.class.wait();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for session writer setup: "
                        + normalized, failure);
            }
        }
        if (registration != null) {
            return registration.owner.readForDiscovery();
        }
        try (var channel = FileChannel.open(normalized, StandardOpenOption.READ)) {
            return new SessionFileReader(normalized, new SessionCodec()).read(channel);
        }
    }

    private static WriterRegistration findWriter(Path path) {
        for (var registration : WRITERS) {
            if (sameFile(registration.path, path)) {
                return registration;
            }
        }
        return null;
    }

    private static boolean sameFile(Path left, Path right) {
        if (left.equals(right)) {
            return true;
        }
        try {
            return Files.isSameFile(left, right);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private static void requireRegistered(WriterRegistration registration) {
        if (!WRITERS.contains(registration)) {
            throw new IllegalStateException("unknown session writer registration");
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    static final class WriterRegistration {
        private final Path path;
        private SessionFile owner;

        private WriterRegistration(Path path) {
            this.path = path;
        }
    }
}
