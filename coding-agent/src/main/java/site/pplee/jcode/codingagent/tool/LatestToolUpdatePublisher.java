package site.pplee.jcode.codingagent.tool;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/** Publishes at most one tool update at a time and coalesces pending snapshots. */
final class LatestToolUpdatePublisher implements AutoCloseable {
    private static final Duration UPDATE_INTERVAL = Duration.ofMillis(100);

    private final Object lock = new Object();
    private final ToolUpdateSink sink;
    private final Supplier<Content> snapshot;
    private final TaskScheduler scheduler;
    private final ExecutorService executor;
    private final Runnable requestStop;

    private boolean dirty;
    private boolean inFlight;
    private boolean finishing;
    private boolean closed;
    private ScheduledTask scheduled;
    private RuntimeException failure;

    LatestToolUpdatePublisher(
            ToolUpdateSink sink,
            Supplier<Content> snapshot,
            TaskScheduler scheduler,
            ExecutorService executor,
            Runnable requestStop
    ) {
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.requestStop = Objects.requireNonNull(requestStop, "requestStop must not be null");
    }

    void markDirty() {
        synchronized (lock) {
            if (closed || failure != null) {
                return;
            }
            dirty = true;
            if (!inFlight && scheduled == null) {
                schedule(Duration.ZERO);
            }
        }
    }

    void finish() {
        boolean interrupted = false;
        synchronized (lock) {
            finishing = true;
            if (scheduled != null) {
                scheduled.cancel();
                scheduled = null;
            }
            if (dirty && !inFlight && failure == null) {
                schedule(Duration.ZERO);
            }
            while ((dirty || inFlight || scheduled != null) && failure == null) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            dirty = false;
            if (scheduled != null) {
                scheduled.cancel();
                scheduled = null;
            }
            lock.notifyAll();
        }
    }

    private void schedule(Duration delay) {
        try {
            scheduled = scheduler.schedule(delay, this::dispatch);
        } catch (RuntimeException e) {
            recordFailure(e);
        }
    }

    private void dispatch() {
        synchronized (lock) {
            scheduled = null;
            if (closed || failure != null || !dirty) {
                lock.notifyAll();
                return;
            }
            dirty = false;
            inFlight = true;
        }
        try {
            executor.execute(this::publish);
        } catch (RuntimeException e) {
            synchronized (lock) {
                inFlight = false;
                recordFailure(e);
            }
        }
    }

    /** Record the first failure under lock and request non-blocking process cancellation. */
    private void recordFailure(RuntimeException cause) {
        if (failure == null) {
            failure = cause;
            dirty = false;
            try {
                requestStop.run();
            } catch (RuntimeException cleanupFailure) {
                if (cleanupFailure != cause) {
                    cause.addSuppressed(cleanupFailure);
                }
            }
        }
        lock.notifyAll();
    }

    private void publish() {
        RuntimeException publishFailure = null;
        try {
            Content update = Objects.requireNonNull(snapshot.get(), "snapshot returned null");
            var stage = sink.update(update);
            if (stage == null) {
                throw new IllegalStateException("tool update sink returned null stage");
            }
            stage.toCompletableFuture().join();
        } catch (CompletionException e) {
            publishFailure = e;
        } catch (RuntimeException e) {
            publishFailure = e;
        }

        synchronized (lock) {
            inFlight = false;
            if (publishFailure != null) {
                recordFailure(publishFailure);
            } else if (dirty && !closed) {
                schedule(finishing ? Duration.ZERO : UPDATE_INTERVAL);
            }
            lock.notifyAll();
        }
    }
}
