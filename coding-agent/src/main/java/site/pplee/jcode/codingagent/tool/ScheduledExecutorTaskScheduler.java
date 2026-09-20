package site.pplee.jcode.codingagent.tool;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-daemon-thread monotonic scheduler used by one Bash tool instance. */
final class ScheduledExecutorTaskScheduler implements TaskScheduler {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "jcode-process-deadlines");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public ScheduledTask schedule(Duration delay, Runnable task) {
        Objects.requireNonNull(delay, "delay must not be null");
        Objects.requireNonNull(task, "task must not be null");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        if (closed.get()) {
            throw new IllegalStateException("scheduler is closed");
        }
        var future = executor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }
}
