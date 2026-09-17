package site.pplee.jcode.aiproviders.openai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic clock and scheduler for retry-budget tests. {@link #advance}
 * moves the clock and runs due tasks; scheduled work never waits on real time.
 * Lives in the production test package so it can construct package-private
 * {@link OpenAiRetrySupport}.
 */
final class ManualRetrySupport {
    private Instant now;
    private final List<Task> tasks = new ArrayList<>();
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now();
        }
    };
    private final ScheduledExecutorService scheduler = new ManualScheduler();

    ManualRetrySupport(Instant start) {
        this.now = start;
    }

    synchronized Instant now() {
        return now;
    }

    synchronized void advance(Duration duration) {
        now = now.plus(duration);
        Instant deadline = now;
        for (Task task : List.copyOf(tasks)) {
            if (!task.when.isAfter(deadline)) {
                tasks.remove(task);
                task.run();
            }
        }
    }

    OpenAiRetrySupport support() {
        return new OpenAiRetrySupport(clock, () -> 1.0d, scheduler, false);
    }

    synchronized boolean hasPending() {
        return !tasks.isEmpty();
    }

    boolean awaitPending(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!hasPending()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(5L);
        }
        return true;
    }

    private final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {
        private volatile boolean shutdown;

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            Instant when = now().plusNanos(unit.toNanos(Math.max(0L, delay)));
            Task task = new Task(when, command);
            synchronized (ManualRetrySupport.this) {
                tasks.add(task);
            }
            return task;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            synchronized (ManualRetrySupport.this) {
                tasks.clear();
            }
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
    }

    private static final class Task implements ScheduledFuture<Void> {
        private final Instant when;
        private final Runnable command;
        private volatile boolean done;
        private volatile boolean cancelled;

        Task(Instant when, Runnable command) {
            this.when = when;
            this.command = command;
        }

        void run() {
            if (cancelled || done) {
                return;
            }
            done = true;
            command.run();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return !done;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Void get() {
            return null;
        }

        @Override
        public Void get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
