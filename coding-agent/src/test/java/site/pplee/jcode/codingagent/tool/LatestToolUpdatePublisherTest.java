package site.pplee.jcode.codingagent.tool;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.message.Content;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LatestToolUpdatePublisherTest {
    @Test
    void allowsOneInFlightUpdateAndPublishesOnlyTheLatestPendingSnapshot() throws Exception {
        var scheduler = new ManualScheduler();
        var firstStage = new CompletableFuture<Void>();
        var snapshots = new AtomicReference<>("first");
        List<String> delivered = new CopyOnWriteArrayList<>();
        var firstDelivered = new CompletableFuture<Void>();
        var secondDelivered = new CompletableFuture<Void>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var publisher = new LatestToolUpdatePublisher(
                     update -> {
                         String text = ((Content.Text) update).text();
                         delivered.add(text);
                         if (delivered.size() == 1) {
                             firstDelivered.complete(null);
                             return firstStage;
                         }
                         secondDelivered.complete(null);
                         return CompletableFuture.completedStage(null);
                     },
                     () -> new Content.Text(snapshots.get()), scheduler, executor,
                     () -> fail("successful delivery must not stop the process"))) {
            publisher.markDirty();
            scheduler.awaitTask();
            scheduler.fireNext();
            firstDelivered.get(5, TimeUnit.SECONDS);

            snapshots.set("stale");
            publisher.markDirty();
            snapshots.set("latest");
            publisher.markDirty();
            assertEquals(List.of("first"), delivered);
            assertEquals(0, scheduler.activeTasks());

            firstStage.complete(null);
            scheduler.awaitTask();
            scheduler.fireNext();
            secondDelivered.get(5, TimeUnit.SECONDS);
            publisher.finish();

            assertEquals(List.of("first", "latest"), delivered);
        } finally {
            scheduler.close();
        }
    }

    @Test
    void failedUpdateStageIsPropagatedByFinish() throws Exception {
        var scheduler = new ManualScheduler();
        var stopped = new CompletableFuture<Void>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var publisher = new LatestToolUpdatePublisher(
                     update -> CompletableFuture.failedStage(
                             new IllegalStateException("sink failed")),
                     () -> new Content.Text("snapshot"), scheduler, executor,
                     () -> stopped.complete(null))) {
            publisher.markDirty();
            scheduler.awaitTask();
            scheduler.fireNext();

            stopped.get(5, TimeUnit.SECONDS);
            var failure = assertThrows(RuntimeException.class, publisher::finish);
            assertTrue(rootCause(failure).getMessage().contains("sink failed"));
        } finally {
            scheduler.close();
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class ManualScheduler implements TaskScheduler {
        private final Object lock = new Object();
        private final ArrayDeque<Entry> entries = new ArrayDeque<>();
        private boolean closed;

        @Override
        public ScheduledTask schedule(Duration delay, Runnable task) {
            var entry = new Entry(task);
            synchronized (lock) {
                if (closed) {
                    throw new IllegalStateException("closed");
                }
                entries.add(entry);
                lock.notifyAll();
            }
            return () -> {
                synchronized (lock) {
                    entry.cancelled = true;
                    lock.notifyAll();
                }
            };
        }

        void awaitTask() throws InterruptedException {
            synchronized (lock) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (entries.stream().noneMatch(entry -> !entry.cancelled)) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        fail("no scheduled task arrived");
                    }
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                }
            }
        }

        void fireNext() {
            Entry entry;
            synchronized (lock) {
                do {
                    entry = entries.removeFirst();
                } while (entry.cancelled);
            }
            entry.task.run();
        }

        int activeTasks() {
            synchronized (lock) {
                return (int) entries.stream().filter(entry -> !entry.cancelled).count();
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                closed = true;
                entries.clear();
                lock.notifyAll();
            }
        }

        private static final class Entry {
            private final Runnable task;
            private boolean cancelled;

            private Entry(Runnable task) {
                this.task = task;
            }
        }
    }
}
