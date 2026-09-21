package site.pplee.jcode.codingagent;

import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.codingagent.session.SessionFormatException;
import site.pplee.jcode.codingagent.session.SessionInfo;
import site.pplee.jcode.codingagent.session.SessionInfoEntry;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Read-only discovery operations for an explicitly supplied session directory. */
public final class SessionFiles {
    private static final Comparator<SessionInfo> LATEST_FIRST =
            Comparator.comparing(SessionInfo::modified)
                    .reversed()
                    .thenComparing(info -> info.path().toString());

    private SessionFiles() {
    }

    /** List all valid direct-child JSONL sessions, latest activity first. */
    public static SessionListResult list(Path directory) throws IOException {
        return listInternal(directory, null);
    }

    /** List valid direct-child JSONL sessions created for the normalized cwd. */
    public static SessionListResult list(Path directory, Path cwdFilter) throws IOException {
        Objects.requireNonNull(cwdFilter, "cwdFilter must not be null");
        return listInternal(directory, cwdFilter.toAbsolutePath().normalize());
    }

    /** Return the latest valid session in the directory without opening it for writing. */
    public static Optional<SessionInfo> latest(Path directory) throws IOException {
        return list(directory).latest();
    }

    /** Return the latest valid session for the normalized cwd. */
    public static Optional<SessionInfo> latest(Path directory, Path cwdFilter) throws IOException {
        return list(directory, cwdFilter).latest();
    }

    private static SessionListResult listInternal(Path directory, Path cwdFilter) throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        var normalizedDirectory = directory.toAbsolutePath().normalize();
        if (Files.notExists(normalizedDirectory)) {
            return new SessionListResult(List.of(), List.of());
        }
        if (!Files.isDirectory(normalizedDirectory)) {
            throw new NotDirectoryException(normalizedDirectory.toString());
        }

        var paths = directJsonlFiles(normalizedDirectory);
        var sessions = new ArrayList<SessionInfo>();
        var diagnostics = new ArrayList<SessionFileDiagnostic>();
        for (var path : paths) {
            scanFile(path, cwdFilter, sessions, diagnostics);
        }
        sessions.sort(LATEST_FIRST);
        return new SessionListResult(sessions, diagnostics);
    }

    private static List<Path> directJsonlFiles(Path directory) throws IOException {
        var paths = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jsonl")) {
            for (var path : stream) {
                if (Files.isRegularFile(path)) {
                    paths.add(path.toAbsolutePath().normalize());
                }
            }
        }
        paths.sort(Comparator.comparing(Path::toString));
        return paths;
    }

    private static void scanFile(
            Path path,
            Path cwdFilter,
            List<SessionInfo> sessions,
            List<SessionFileDiagnostic> diagnostics
    ) {
        try {
            var loaded = SessionFileAccess.read(path);
            var info = summarize(path, loaded);
            if (cwdFilter == null || info.cwd().equals(cwdFilter)) {
                sessions.add(info);
            }
            if (loaded.recovery() != null) {
                var recovery = loaded.recovery();
                diagnostics.add(new SessionFileDiagnostic(
                        path,
                        SessionFileDiagnostic.Kind.RECOVERED_TAIL,
                        recovery.lineNumber(),
                        recovery.byteOffset(),
                        recovery.reason() + ", discardedBytes=" + recovery.discardedBytes()));
            }
        } catch (SessionFormatException failure) {
            diagnostics.add(new SessionFileDiagnostic(
                    path,
                    SessionFileDiagnostic.Kind.INVALID_SESSION,
                    failure.lineNumber(),
                    failure.byteOffset(),
                    failure.reason()));
        } catch (IOException failure) {
            diagnostics.add(new SessionFileDiagnostic(
                    path,
                    SessionFileDiagnostic.Kind.READ_FAILURE,
                    SessionFileDiagnostic.UNKNOWN_POSITION,
                    SessionFileDiagnostic.UNKNOWN_POSITION,
                    ioDetail(failure)));
        }
    }

    private static SessionInfo summarize(Path path, SessionFileReader.ReadResult loaded) {
        String name = null;
        long messageCount = 0;
        Instant latestActivity = null;
        for (var entry : loaded.entries()) {
            if (entry instanceof SessionInfoEntry info) {
                name = info.name();
            }
            if (entry instanceof SessionMessageEntry messageEntry) {
                messageCount++;
                StandardAgentMessage standard = messageEntry.message();
                if (standard.message() instanceof Message.User
                        || standard.message() instanceof Message.Assistant) {
                    latestActivity = latestActivity == null
                            ? messageEntry.timestamp()
                            : max(latestActivity, messageEntry.timestamp());
                }
            }
        }
        return new SessionInfo(
                path,
                loaded.header().id(),
                loaded.header().cwd(),
                Optional.ofNullable(name),
                loaded.header().timestamp(),
                latestActivity == null ? loaded.header().timestamp() : latestActivity,
                messageCount);
    }

    private static Instant max(Instant left, Instant right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private static String ioDetail(IOException failure) {
        var message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }
}
