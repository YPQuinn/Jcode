package site.pplee.jcode.aiproviders.openai;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * Package-private retry decision and delay helpers. Status and allowlisted
 * headers decide retryability; error-message text is never consulted.
 */
final class OpenAiRetry {
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);

    private OpenAiRetry() {
    }

    /**
     * Retryability for a non-2xx response. {@code x-should-retry} true/false
     * overrides the status table; otherwise 408/409/429/5xx retry and other
     * 4xx do not.
     */
    static boolean isRetryable(OpenAiHttpError error) {
        Objects.requireNonNull(error, "error must not be null");
        Optional<Boolean> override = error.shouldRetryOverride();
        if (override.isPresent()) {
            return override.get();
        }
        int status = error.status();
        return status == 408 || status == 409 || status == 429 || (status >= 500 && status <= 599);
    }

    /** Connect/send failures without an HTTP status are retryable. */
    static boolean isRetryableTransport() {
        return true;
    }

    /**
     * Synchronous {@code sendAsync} throw that is a true transport failure.
     * Programming errors ({@code IllegalArgumentException}, NPE, etc.) are
     * not transport and must not be retried.
     */
    static boolean isSynchronousTransportFailure(RuntimeException error) {
        return error instanceof UncheckedIOException;
    }

    static Delay delayForHttp(
            OpenAiHttpError error,
            int retryIndex,
            OpenAiRetryPolicy policy,
            Instant now,
            double jitterUnit
    ) {
        Objects.requireNonNull(error, "error must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Optional<Duration> server = serverDelay(error, now);
        if (server.isPresent()) {
            Duration requested = normalizeNonNegative(server.get());
            if (requested.compareTo(policy.maxServerDelay()) > 0) {
                return Delay.exceedsServerMax(requested);
            }
            return Delay.server(requested);
        }
        return Delay.backoff(exponentialBackoff(retryIndex, policy, jitterUnit));
    }

    static Delay delayForTransport(int retryIndex, OpenAiRetryPolicy policy, double jitterUnit) {
        Objects.requireNonNull(policy, "policy must not be null");
        return Delay.backoff(exponentialBackoff(retryIndex, policy, jitterUnit));
    }

    /**
     * True when the wall-clock budget from {@code started} is already
     * exhausted at {@code now}, including a zero budget. A zero planned
     * wait still cannot start a retry after the deadline.
     */
    static boolean exceedsRemainingBudget(Duration planned, Instant started, Instant now, Optional<Duration> budget) {
        Objects.requireNonNull(planned, "planned must not be null");
        Objects.requireNonNull(started, "started must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (budget == null || budget.isEmpty()) {
            return false;
        }
        Duration elapsed = elapsedSince(started, now);
        Duration total = budget.get();
        if (elapsed.compareTo(total) >= 0) {
            return true;
        }
        Duration remaining;
        try {
            remaining = total.minus(elapsed);
        } catch (ArithmeticException e) {
            return true;
        }
        if (remaining.isNegative()) {
            return true;
        }
        return planned.compareTo(remaining) > 0;
    }

    /**
     * True when elapsed wall-clock time has reached the budget. Used after
     * a wait completes and before the next attempt is sent. Clock rollback
     * is treated as zero elapsed so a backward jump cannot skip the check
     * by inventing negative time.
     */
    static boolean budgetExhausted(Instant started, Instant now, Optional<Duration> budget) {
        return exceedsRemainingBudget(Duration.ZERO, started, now, budget);
    }

    static Duration elapsedSince(Instant started, Instant now) {
        Objects.requireNonNull(started, "started must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (now.compareTo(started) <= 0) {
            return Duration.ZERO;
        }
        try {
            return Duration.between(started, now);
        } catch (RuntimeException e) {
            return Duration.ofSeconds(Long.MAX_VALUE);
        }
    }

    static Optional<Duration> serverDelay(OpenAiHttpError error, Instant now) {
        Optional<Duration> fromMs = parseMilliseconds(error.retryAfterMs().orElse(null));
        if (fromMs.isPresent()) {
            return fromMs;
        }
        String retryAfter = error.retryAfter().orElse(null);
        Optional<Duration> fromSeconds = parseSeconds(retryAfter);
        if (fromSeconds.isPresent()) {
            return fromSeconds;
        }
        return parseHttpDateDelay(retryAfter, now);
    }

    static Duration exponentialBackoff(int retryIndex, OpenAiRetryPolicy policy, double jitterUnit) {
        int index = Math.max(0, retryIndex);
        Duration raw;
        if (index >= 44) {
            raw = policy.maxBackoff();
        } else {
            raw = INITIAL_BACKOFF.multipliedBy(1L << index);
            if (raw.compareTo(policy.maxBackoff()) > 0) {
                raw = policy.maxBackoff();
            }
        }
        double unit = clampUnit(jitterUnit);
        double factor = policy.jitterMin() + (policy.jitterMax() - policy.jitterMin()) * unit;
        return scale(raw, factor);
    }

    private static Optional<Duration> parseMilliseconds(String raw) {
        Optional<Double> value = parseFiniteNumber(raw);
        return value.map(millis -> Duration.ofMillis((long) millis.doubleValue()));
    }

    private static Optional<Duration> parseSeconds(String raw) {
        Optional<Double> value = parseFiniteNumber(raw);
        return value.map(seconds -> Duration.ofMillis((long) (seconds.doubleValue() * 1000.0d)));
    }

    private static Optional<Duration> parseHttpDateDelay(String raw, Instant now) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            Instant when = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(raw.trim()));
            return Optional.of(Duration.between(now, when));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Double> parseFiniteNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        try {
            return Optional.of((double) Long.parseLong(trimmed));
        } catch (NumberFormatException ignored) {
            try {
                double value = Double.parseDouble(trimmed);
                if (!Double.isFinite(value)) {
                    return Optional.empty();
                }
                return Optional.of(value);
            } catch (NumberFormatException ignoredAgain) {
                return Optional.empty();
            }
        }
    }

    private static Duration normalizeNonNegative(Duration value) {
        return value.isNegative() ? Duration.ZERO : value;
    }

    private static double clampUnit(double jitterUnit) {
        if (!Double.isFinite(jitterUnit)) {
            return 0.0d;
        }
        if (jitterUnit < 0.0d) {
            return 0.0d;
        }
        if (jitterUnit > 1.0d) {
            return 1.0d;
        }
        return jitterUnit;
    }

    private static Duration scale(Duration duration, double factor) {
        if (duration.isZero() || factor <= 0.0d) {
            return Duration.ZERO;
        }
        double nanos = duration.toNanos() * factor;
        if (!Double.isFinite(nanos) || nanos <= 0.0d) {
            return Duration.ZERO;
        }
        if (nanos >= (double) Long.MAX_VALUE) {
            return duration;
        }
        return Duration.ofNanos((long) nanos);
    }

    /**
     * Computed wait before the next attempt. {@link Kind#SERVER_EXCEEDS_MAX}
     * is a final failure: the adapter must not wait or retry.
     */
    record Delay(Kind kind, Duration duration) {
        Delay {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(duration, "duration must not be null");
            if (duration.isNegative()) {
                duration = Duration.ZERO;
            }
        }

        static Delay backoff(Duration duration) {
            return new Delay(Kind.BACKOFF, duration);
        }

        static Delay server(Duration duration) {
            return new Delay(Kind.SERVER, duration);
        }

        static Delay exceedsServerMax(Duration duration) {
            return new Delay(Kind.SERVER_EXCEEDS_MAX, duration);
        }

        boolean exceedsServerMax() {
            return kind == Kind.SERVER_EXCEEDS_MAX;
        }
    }

    enum Kind {
        BACKOFF,
        SERVER,
        SERVER_EXCEEDS_MAX
    }
}
