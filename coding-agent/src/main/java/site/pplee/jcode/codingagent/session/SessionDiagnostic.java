package site.pplee.jcode.codingagent.session;

import java.nio.file.Path;
import java.util.Objects;

/** Structured, content-free diagnostic produced while opening a session. */
public sealed interface SessionDiagnostic
        permits SessionDiagnostic.WorkingDirectoryMismatch, SessionDiagnostic.RecoveredTail {

    /** The file was created for a different working directory than the active configuration. */
    record WorkingDirectoryMismatch(Path recorded, Path configured) implements SessionDiagnostic {
        public WorkingDirectoryMismatch {
            recorded = normalize(recorded, "recorded");
            configured = normalize(configured, "configured");
        }
    }

    /** A confirmed incomplete EOF suffix was ignored and will be removed before the next append. */
    record RecoveredTail(
            Path sessionFile,
            long lineNumber,
            long byteOffset,
            long discardedBytes,
            Reason reason
    ) implements SessionDiagnostic {
        public RecoveredTail {
            sessionFile = normalize(sessionFile, "sessionFile");
            if (lineNumber < 1) {
                throw new IllegalArgumentException("lineNumber must be positive");
            }
            if (byteOffset < 0 || discardedBytes < 1) {
                throw new IllegalArgumentException(
                        "byteOffset must be non-negative and discardedBytes must be positive");
            }
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** Stable reason for dropping an incomplete EOF suffix on the next append. */
    enum Reason {
        TRUNCATED_JSON,
        INCOMPLETE_UTF8
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name + " must not be null")
                .toAbsolutePath()
                .normalize();
    }
}
