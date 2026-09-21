package site.pplee.jcode.codingagent.session;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Identifies the physical location of an invalid session JSONL record. */
public final class SessionFormatException extends IOException {
    private final Path path;
    private final long lineNumber;
    private final long byteOffset;
    private final String reason;

    /** Create a format failure at a physical line and byte offset. */
    public SessionFormatException(
            Path path,
            long lineNumber,
            long byteOffset,
            String reason,
            Throwable cause
    ) {
        super(message(path, lineNumber, byteOffset, reason), cause);
        this.path = Objects.requireNonNull(path, "path must not be null");
        this.lineNumber = lineNumber;
        this.byteOffset = byteOffset;
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    /** Invalid session file. */
    public Path path() {
        return path;
    }

    /** One-based physical line number. */
    public long lineNumber() {
        return lineNumber;
    }

    /** Zero-based byte offset of the physical line. */
    public long byteOffset() {
        return byteOffset;
    }

    /** Content-free validation reason reported by the codec or reader. */
    public String reason() {
        return reason;
    }

    private static String message(Path path, long lineNumber, long byteOffset, String reason) {
        return "invalid session file " + path + " at line " + lineNumber
                + ", byte " + byteOffset + ": " + reason;
    }
}
