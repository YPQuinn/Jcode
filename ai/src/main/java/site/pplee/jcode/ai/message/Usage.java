package site.pplee.jcode.ai.message;

import java.util.Objects;
import java.util.Optional;

/**
 * Token-usage metadata reported with a model result. Part of the standard
 * model-result metadata owned by {@code ai} (provider/api/model identity,
 * usage, stop reason, error message).
 *
 * <p>Wired into {@link Message.Assistant#usage()} as the canonical value type
 * for token-usage metadata. Adapters provide real usage in the final
 * {@code Done}/{@code Error} event; partial messages use {@link #zero()}.
 *
 * <p>{@link #reasoningTokens()} is a non-negative subset of {@link #output()}
 * when the provider reports it. {@link #cost()} is present only when a
 * caller-supplied price table produced an estimate; it is never a zeroed
 * placeholder for “not priced”.
 */
public record Usage(
        long input,
        long output,
        long cacheRead,
        long cacheWrite,
        long totalTokens,
        long reasoningTokens,
        Optional<CostEstimate> cost
) {
    public Usage {
        if (totalTokens < 0) {
            throw new IllegalArgumentException("totalTokens must not be negative");
        }
        if (input < 0 || output < 0 || cacheRead < 0 || cacheWrite < 0 || reasoningTokens < 0) {
            throw new IllegalArgumentException("usage fields must not be negative");
        }
        if (reasoningTokens > output) {
            throw new IllegalArgumentException("reasoningTokens must not exceed output");
        }
        Objects.requireNonNull(cost, "cost must not be null");
    }

    /**
     * Compatibility constructor: no reasoning-token breakdown and no cost
     * estimate.
     */
    public Usage(long input, long output, long cacheRead, long cacheWrite, long totalTokens) {
        this(input, output, cacheRead, cacheWrite, totalTokens, 0L, Optional.empty());
    }

    /**
     * A zeroed usage for tests and “no usage reported” sentinels. Cost is
     * absent; callers must not treat this as a priced zero estimate.
     */
    public static Usage zero() {
        return new Usage(0, 0, 0, 0, 0);
    }

    /** Copy of this usage with an explicit cost estimate. */
    public Usage withCost(CostEstimate cost) {
        return new Usage(input, output, cacheRead, cacheWrite, totalTokens, reasoningTokens, Optional.of(cost));
    }
}
