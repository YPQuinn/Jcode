package site.pplee.jcode.aiproviders.openai;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, opt-in retry bounds for OpenAI Responses HTTP attempts.
 * The default policy keeps a single attempt. {@link #toString()} reports
 * numeric bounds only and never contains credentials or header values.
 */
public final class OpenAiRetryPolicy {
    /** Inclusive upper bound for {@link #maxRetries()}. */
    public static final int MAX_RETRIES_BOUND = 32;
    /** Inclusive upper bound for server-delay and exponential-backoff caps. */
    public static final Duration MAX_DELAY_BOUND = Duration.ofMinutes(10);
    /** Inclusive upper bound for an optional total retry budget. */
    public static final Duration MAX_BUDGET_BOUND = Duration.ofMinutes(30);

    static final Duration DEFAULT_MAX_SERVER_DELAY = Duration.ofSeconds(60);
    static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(8);
    static final double DEFAULT_JITTER_MIN = 0.75d;
    static final double DEFAULT_JITTER_MAX = 1.0d;

    private static final OpenAiRetryPolicy DISABLED = new OpenAiRetryPolicy(
            0,
            DEFAULT_MAX_SERVER_DELAY,
            DEFAULT_MAX_BACKOFF,
            DEFAULT_JITTER_MIN,
            DEFAULT_JITTER_MAX,
            Optional.empty());

    private final int maxRetries;
    private final Duration maxServerDelay;
    private final Duration maxBackoff;
    private final double jitterMin;
    private final double jitterMax;
    private final Optional<Duration> totalBudget;

    private OpenAiRetryPolicy(
            int maxRetries,
            Duration maxServerDelay,
            Duration maxBackoff,
            double jitterMin,
            double jitterMax,
            Optional<Duration> totalBudget
    ) {
        this.maxRetries = requireMaxRetries(maxRetries);
        this.maxServerDelay = requireBoundedDuration("maxServerDelay", maxServerDelay, MAX_DELAY_BOUND);
        this.maxBackoff = requireBoundedDuration("maxBackoff", maxBackoff, MAX_DELAY_BOUND);
        requireJitterRange(jitterMin, jitterMax);
        this.jitterMin = jitterMin;
        this.jitterMax = jitterMax;
        this.totalBudget = requireBudget(totalBudget);
    }

    /**
     * Default policy: {@code maxRetries = 0}, so the adapter sends one
     * request and never waits to retry.
     */
    public static OpenAiRetryPolicy disabled() {
        return DISABLED;
    }

    /** Alias of {@link #disabled()} for call sites that want explicit defaults. */
    public static OpenAiRetryPolicy defaults() {
        return DISABLED;
    }

    /**
     * Policy that allows {@code maxRetries} retries and otherwise uses
     * the default delay, backoff, and jitter bounds.
     */
    public static OpenAiRetryPolicy of(int maxRetries) {
        return builder().maxRetries(maxRetries).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Number of retries after the first attempt. {@code 0} means one request. */
    public int maxRetries() {
        return maxRetries;
    }

    /**
     * Maximum server-requested wait. A parsed {@code retry-after-ms} or
     * {@code Retry-After} delay above this value fails immediately.
     */
    public Duration maxServerDelay() {
        return maxServerDelay;
    }

    /** Cap applied to the exponential backoff before jitter. */
    public Duration maxBackoff() {
        return maxBackoff;
    }

    /** Inclusive lower jitter multiplier applied to exponential backoff. */
    public double jitterMin() {
        return jitterMin;
    }

    /** Inclusive upper jitter multiplier applied to exponential backoff. */
    public double jitterMax() {
        return jitterMax;
    }

    /**
     * Optional wall-clock budget covering backoff waits from the first
     * attempt. Empty means no budget. A wait that would exceed the
     * remaining budget fails immediately.
     */
    public Optional<Duration> totalBudget() {
        return totalBudget;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof OpenAiRetryPolicy other)) {
            return false;
        }
        return maxRetries == other.maxRetries
                && Double.doubleToLongBits(jitterMin) == Double.doubleToLongBits(other.jitterMin)
                && Double.doubleToLongBits(jitterMax) == Double.doubleToLongBits(other.jitterMax)
                && maxServerDelay.equals(other.maxServerDelay)
                && maxBackoff.equals(other.maxBackoff)
                && totalBudget.equals(other.totalBudget);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxRetries, maxServerDelay, maxBackoff, jitterMin, jitterMax, totalBudget);
    }

    @Override
    public String toString() {
        return "OpenAiRetryPolicy[maxRetries=" + maxRetries
                + ", maxServerDelay=" + maxServerDelay
                + ", maxBackoff=" + maxBackoff
                + ", jitterMin=" + jitterMin
                + ", jitterMax=" + jitterMax
                + ", totalBudget=" + totalBudget
                + "]";
    }

    private static int requireMaxRetries(int maxRetries) {
        if (maxRetries < 0 || maxRetries > MAX_RETRIES_BOUND) {
            throw new IllegalArgumentException(
                    "maxRetries must be between 0 and " + MAX_RETRIES_BOUND);
        }
        return maxRetries;
    }

    private static Duration requireBoundedDuration(String name, Duration value, Duration max) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        if (value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " exceeds maximum of " + max);
        }
        return value;
    }

    private static void requireJitterRange(double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            throw new IllegalArgumentException("jitter range must be finite");
        }
        if (min < 0.0d || max > 1.0d || min > max) {
            throw new IllegalArgumentException("jitter range must satisfy 0 <= min <= max <= 1");
        }
    }

    private static Optional<Duration> requireBudget(Optional<Duration> budget) {
        Optional<Duration> present = budget == null ? Optional.empty() : budget;
        present.ifPresent(value -> requireBoundedDuration("totalBudget", value, MAX_BUDGET_BOUND));
        return present;
    }

    public static final class Builder {
        private int maxRetries;
        private Duration maxServerDelay = DEFAULT_MAX_SERVER_DELAY;
        private Duration maxBackoff = DEFAULT_MAX_BACKOFF;
        private double jitterMin = DEFAULT_JITTER_MIN;
        private double jitterMax = DEFAULT_JITTER_MAX;
        private Optional<Duration> totalBudget = Optional.empty();

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder maxServerDelay(Duration maxServerDelay) {
            this.maxServerDelay = maxServerDelay;
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        /**
         * Inclusive jitter multipliers applied to exponential backoff.
         * {@code 0.75, 1.0} keeps between 75% and 100% of the capped delay.
         */
        public Builder jitterRange(double min, double max) {
            this.jitterMin = min;
            this.jitterMax = max;
            return this;
        }

        public Builder totalBudget(Duration totalBudget) {
            this.totalBudget = Optional.ofNullable(totalBudget);
            return this;
        }

        public OpenAiRetryPolicy build() {
            return new OpenAiRetryPolicy(
                    maxRetries, maxServerDelay, maxBackoff, jitterMin, jitterMax, totalBudget);
        }
    }
}
