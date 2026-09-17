package site.pplee.jcode.aiproviders.openai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

/**
 * Package-private clock, jitter, and scheduler seam for retry backoff.
 * Production code uses {@link #createDefault()}; tests may inject fakes.
 * This type is not part of the public provider API.
 */
final class OpenAiRetrySupport implements AutoCloseable {
    private final Clock clock;
    private final DoubleSupplier jitter;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    OpenAiRetrySupport(
            Clock clock,
            DoubleSupplier jitter,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.jitter = Objects.requireNonNull(jitter, "jitter must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.ownsScheduler = ownsScheduler;
    }

    static OpenAiRetrySupport createDefault() {
        return new OpenAiRetrySupport(
                Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble(),
                newScheduler(),
                true);
    }

    Instant now() {
        return clock.instant();
    }

    double nextJitterUnit() {
        return jitter.getAsDouble();
    }

    ScheduledFuture<?> schedule(Runnable command, Duration delay) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(delay, "delay must not be null");
        long nanos = delay.isNegative() ? 0L : delay.toNanos();
        return scheduler.schedule(command, nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void close() {
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "openai-responses-retry");
            thread.setDaemon(true);
            return thread;
        });
    }
}
