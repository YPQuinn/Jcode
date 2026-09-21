package site.pplee.jcode.codingagent;

import java.nio.file.Path;
import java.util.Objects;

/** Content-free diagnostic for one file encountered during a directory scan. */
public record SessionFileDiagnostic(
        Path path,
        Kind kind,
        long lineNumber,
        long byteOffset,
        String detail
) {
    /** Position used when a generic I/O failure has no JSONL record location. */
    public static final long UNKNOWN_POSITION = -1;

    public SessionFileDiagnostic {
        path = Objects.requireNonNull(path, "path must not be null")
                .toAbsolutePath()
                .normalize();
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        if ((lineNumber == UNKNOWN_POSITION) != (byteOffset == UNKNOWN_POSITION)) {
            throw new IllegalArgumentException("lineNumber and byteOffset must both be known or unknown");
        }
        if (lineNumber != UNKNOWN_POSITION && (lineNumber < 1 || byteOffset < 0)) {
            throw new IllegalArgumentException("invalid diagnostic position");
        }
    }

    public enum Kind {
        INVALID_SESSION,
        READ_FAILURE,
        RECOVERED_TAIL
    }
}
