package site.pplee.jcode.codingagent.tool;

import java.util.Objects;

/** Immutable bounded tail snapshot of decoded process output. */
record ProcessOutputSnapshot(
        String content,
        long totalLines,
        long totalUtf8Bytes,
        int outputLines,
        int outputUtf8Bytes,
        boolean truncated,
        TruncatedBy truncatedBy,
        boolean firstLinePartial,
        boolean invalidUtf8
) {
    ProcessOutputSnapshot {
        content = Objects.requireNonNull(content, "content must not be null");
        if (totalLines < 0 || totalUtf8Bytes < 0 || outputLines < 0 || outputUtf8Bytes < 0) {
            throw new IllegalArgumentException("output counters must not be negative");
        }
        if (truncated && truncatedBy == null) {
            throw new IllegalArgumentException("truncated snapshot must identify its limit");
        }
        if (!truncated && truncatedBy != null) {
            throw new IllegalArgumentException("complete snapshot must not identify a limit");
        }
    }

    enum TruncatedBy {
        LINES,
        BYTES
    }
}
