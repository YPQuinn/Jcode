package site.pplee.jcode.codingagent.compaction;

import site.pplee.jcode.codingagent.session.TokenEstimateSource;

import java.util.Objects;
import java.util.OptionalInt;

/** Public point-in-time estimate of the effective model request size. */
public record ContextUsageEstimate(
        long tokens,
        TokenEstimateSource source,
        OptionalInt contextWindow,
        OptionalInt threshold
) {
    public ContextUsageEstimate {
        if (tokens < 0) {
            throw new IllegalArgumentException("tokens must not be negative");
        }
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(contextWindow, "contextWindow must not be null");
        Objects.requireNonNull(threshold, "threshold must not be null");
    }
}
