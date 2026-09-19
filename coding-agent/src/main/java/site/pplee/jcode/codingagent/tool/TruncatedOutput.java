package site.pplee.jcode.codingagent.tool;

import java.util.Objects;
import java.util.OptionalInt;

/** Immutable result of bounded, whole-line head truncation. */
public record TruncatedOutput(
        String content,
        boolean truncated,
        int outputLines,
        long outputBytes,
        OptionalInt nextOffset
) {
    public TruncatedOutput {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(nextOffset, "nextOffset must not be null");
        if (outputLines < 0 || outputBytes < 0) {
            throw new IllegalArgumentException("output counts must not be negative");
        }
        if (truncated != nextOffset.isPresent()) {
            throw new IllegalArgumentException("nextOffset must be present exactly when output is truncated");
        }
    }
}
