package site.pplee.jcode.codingagent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ProcessRunnerTest {
    private static final ProcessRequest REQUEST = new ProcessRequest(
            List.of("/bin/test", "arg"), Path.of("/tmp"), Map.of(),
            Duration.ofSeconds(10), ProcessRequest.OutputMode.MERGED);

    @Test
    void normalExitDrainsOutputAndLateCancellationCannotChangeTheWinner() {
        var process = FakeManagedProcess.exited(7, "done");
        var scheduler = new ManualTaskScheduler();
        var output = new java.io.ByteArrayOutputStream();

        try (var runner = runner(request -> process, scheduler)) {
            var cancellation = new MutableCancellationSignal();
            var result = runner.run(REQUEST, (channel, bytes, offset, length) -> output.write(bytes, offset, length), cancellation);
            cancellation.cancel();

            assertEquals(ProcessRunResult.Termination.EXITED, result.termination());
            assertEquals(7, result.exitCode());
            assertEquals("done", output.toString(java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(process.gracefulTermination.get());
            assertEquals(0, scheduler.activeTasks());
        }
    }

    @Test
    void timeoutWinsAndRequestsNonBlockingTreeTermination() throws Exception {
        var process = new FakeManagedProcess(new ByteArrayInputStream(new byte[0]));
        var scheduler = new ManualTaskScheduler();
        var cancellation = new MutableCancellationSignal();
        var launched = new CountDownLatch(1);

        try (var runner = runner(request -> {
                 launched.countDown();
                 return process;
             }, scheduler);
             var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = CompletableFuture.supplyAsync(
                    () -> runner.run(REQUEST, (channel, bytes, offset, length) -> { }, cancellation), caller);
            assertTrue(launched.await(5, TimeUnit.SECONDS));
            scheduler.awaitTask();
            scheduler.fireNext();

            assertEquals(ProcessRunResult.Termination.TIMED_OUT,
                    result.get(5, TimeUnit.SECONDS).termination());
            assertTrue(process.terminationRequested.await(5, TimeUnit.SECONDS));
            assertTrue(process.gracefulTermination.get(),
                    () -> "expected graceful termination before force; force="
                            + process.forcefulTermination.get());
        }
    }

    @Test
    void forceEscalationRunsWhenGracefulTerminationDoesNotExit() throws Exception {
        var process = new FakeManagedProcess(new ByteArrayInputStream(new byte[0]));
        process.ignoreGraceful = true;
        var scheduler = new ManualTaskScheduler();
        var launched = new CountDownLatch(1);

        try (var runner = runner(request -> {
                 launched.countDown();
                 return process;
             }, scheduler);
             var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = CompletableFuture.supplyAsync(
                    () -> runner.run(REQUEST, (channel, bytes, offset, length) -> { },
                            new MutableCancellationSignal()), caller);
            assertTrue(launched.await(5, TimeUnit.SECONDS));
            scheduler.awaitTask();
            scheduler.fireNext();
            assertTrue(process.terminationRequested.await(5, TimeUnit.SECONDS));
            scheduler.awaitTask();
            scheduler.fireNext();

            assertEquals(ProcessRunResult.Termination.TIMED_OUT,
                    result.get(5, TimeUnit.SECONDS).termination());
            assertTrue(process.forcefulTermination.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stoppingStillForcesTheChildAfterTheParentExits(boolean cancel) throws Exception {
        var process = new FakeManagedProcess(new ByteArrayInputStream(new byte[0]));
        process.hasResistantChild = true;
        var scheduler = new ManualTaskScheduler();
        var cancellation = new MutableCancellationSignal();

        try (var runner = runner(request -> process, scheduler);
             var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = CompletableFuture.supplyAsync(
                    () -> runner.run(REQUEST, (channel, bytes, offset, length) -> { },
                            cancellation), caller);
            try {
                assertTrue(process.outputRequested.await(5, TimeUnit.SECONDS));
                if (cancel) {
                    cancellation.cancel();
                }
                scheduler.fireNext(); // Command deadline wins only if cancellation has not won.
                assertTrue(process.terminationRequested.await(5, TimeUnit.SECONDS));
                assertTrue(process.exitCode.isDone());
                assertFalse(result.isDone(), "the parent's exit does not settle its surviving child");

                scheduler.fireNext();
                var expected = cancel ? ProcessRunResult.Termination.CANCELLED
                        : ProcessRunResult.Termination.TIMED_OUT;
                assertEquals(expected, result.get(5, TimeUnit.SECONDS).termination());
                assertTrue(process.forcefulTermination.get());
                assertTrue(process.childExit.isDone());
            } finally {
                process.exitCode.complete(-1);
                process.childExit.complete(null);
            }
        }
    }

    @Test
    void closeKeepsTheTerminationDeadlineUntilAnUnkillableTreeSettles() throws Exception {
        var process = new FakeManagedProcess(new ByteArrayInputStream(new byte[0]));
        process.hasResistantChild = true;
        process.ignoreForceful = true;
        var scheduler = new ManualTaskScheduler();
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        try (var runner = new ProcessRunner(request -> process, scheduler, executor, 1, true);
             var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = CompletableFuture.supplyAsync(
                    () -> runner.run(REQUEST, (channel, bytes, offset, length) -> { },
                            new MutableCancellationSignal()), caller);
            try {
                assertTrue(process.outputRequested.await(5, TimeUnit.SECONDS));
                runner.close();
                assertTrue(process.terminationRequested.await(5, TimeUnit.SECONDS));
                assertFalse(result.isDone());
                assertFalse(executor.isShutdown(), "cleanup resources must outlive the active run");

                scheduler.fireNext(); // Original command deadline cannot change the cancellation winner.
                scheduler.fireNext(); // Force escalation still cannot kill the child.
                scheduler.fireNext(); // Termination deadline bounds cleanup.
                assertEquals(ProcessRunResult.Termination.TERMINATION_FAILED,
                        result.get(5, TimeUnit.SECONDS).termination());
                assertTrue(executor.isShutdown());
            } finally {
                process.exitCode.complete(-1);
                process.childExit.complete(null);
            }
        }
    }

    @Test
    void failedTreeExitStageIsReportedWithoutWaitingForTheCommandDeadline() throws Exception {
        var process = new FakeManagedProcess(new ByteArrayInputStream(new byte[0]));
        process.hasResistantChild = true;
        process.childExit.completeExceptionally(new IllegalStateException("exit observation failed"));
        var scheduler = new ManualTaskScheduler();

        try (var runner = runner(request -> process, scheduler);
             var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = CompletableFuture.supplyAsync(
                    () -> runner.run(REQUEST, (channel, bytes, offset, length) -> { },
                            new MutableCancellationSignal()), caller);
            try {
                assertTrue(process.outputRequested.await(5, TimeUnit.SECONDS));
                runner.close();
                assertEquals(ProcessRunResult.Termination.TERMINATION_FAILED,
                        result.get(5, TimeUnit.SECONDS).termination());
                assertTrue(process.forcefulTermination.get());
            } finally {
                process.exitCode.complete(-1);
            }
        }
    }

    @Test
    void cancellationWinsWhileLaunchIsStillBlocked() throws Exception {
        var process = new FakeManagedProcess(new ByteArrayInputStream(new byte[0]));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ProcessLauncher launcher = request -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("launch interrupted", e);
            }
            return process;
        };
        var scheduler = new ManualTaskScheduler();
        var cancellation = new MutableCancellationSignal();

        try (var runner = runner(launcher, scheduler);
             var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = CompletableFuture.supplyAsync(
                    () -> runner.run(REQUEST, (channel, bytes, offset, length) -> { }, cancellation), caller);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            cancellation.cancel();
            release.countDown();

            assertEquals(ProcessRunResult.Termination.CANCELLED,
                    result.get(5, TimeUnit.SECONDS).termination());
            assertTrue(process.gracefulTermination.get());
        }
    }

    @Test
    void closeWhileLaunchIsBlockedForcesTheEventuallyStartedProcess() throws Exception {
        var process = new FakeManagedProcess(new ByteArrayInputStream(new byte[0]));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ProcessLauncher launcher = request -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("launch interrupted", e);
            }
            return process;
        };
        var scheduler = new ManualTaskScheduler();

        try (var runner = runner(launcher, scheduler);
             var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = CompletableFuture.supplyAsync(
                    () -> runner.run(REQUEST, (channel, bytes, offset, length) -> { },
                            new MutableCancellationSignal()), caller);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            runner.close();
            release.countDown();

            assertEquals(ProcessRunResult.Termination.CANCELLED,
                    result.get(5, TimeUnit.SECONDS).termination());
            assertTrue(process.forcefulTermination.get());
        }
    }

    @Test
    void preCancelledRequestDoesNotLaunchOrConsumeCapacity() {
        var launches = new java.util.concurrent.atomic.AtomicInteger();
        ProcessLauncher launcher = request -> {
            launches.incrementAndGet();
            return FakeManagedProcess.exited(0, "");
        };
        var scheduler = new ManualTaskScheduler();

        try (var runner = runner(launcher, scheduler)) {
            var cancelled = new MutableCancellationSignal();
            cancelled.cancel();
            assertEquals(ProcessRunResult.Termination.CANCELLED,
                    runner.run(REQUEST, (channel, bytes, offset, length) -> { }, cancelled).termination());
            assertEquals(ProcessRunResult.Termination.EXITED,
                    runner.run(REQUEST, (channel, bytes, offset, length) -> { },
                            new MutableCancellationSignal()).termination());
            assertEquals(1, launches.get());
        }
    }

    @Test
    void separatelyDrainsAndLabelsBothOutputPipes() {
        var process = FakeManagedProcess.exited(
                0,
                new ByteArrayInputStream("out".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new ByteArrayInputStream("err".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var chunks = new CopyOnWriteChunkList();
        var request = new ProcessRequest(
                REQUEST.command(), REQUEST.workingDirectory(), REQUEST.environment(),
                REQUEST.timeout(), ProcessRequest.OutputMode.SEPARATE);

        try (var runner = runner(ignored -> process, new ManualTaskScheduler())) {
            var result = runner.run(request, chunks::add, new MutableCancellationSignal());

            assertEquals(ProcessRunResult.Termination.EXITED, result.termination());
            assertTrue(chunks.values.contains("STANDARD_OUTPUT:out"));
            assertTrue(chunks.values.contains("STANDARD_ERROR:err"));
        }
    }

    @Test
    void closesPipesWhenBoundedPostExitDrainExpires() throws Exception {
        var blockingOutput = new CloseReleasedInputStream();
        var process = FakeManagedProcess.exited(0, blockingOutput);
        var scheduler = new ManualTaskScheduler();

        try (var runner = runner(request -> process, scheduler);
             var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = CompletableFuture.supplyAsync(
                    () -> runner.run(REQUEST, (channel, bytes, offset, length) -> { },
                            new MutableCancellationSignal()), caller);
            assertTrue(blockingOutput.readEntered.await(5, TimeUnit.SECONDS));
            scheduler.awaitTask();
            scheduler.fireNext();

            var outcome = result.get(5, TimeUnit.SECONDS);
            assertEquals(ProcessRunResult.Termination.FAILED, outcome.termination());
            assertEquals("output drain did not complete", outcome.diagnostic().orElseThrow());
            assertTrue(blockingOutput.closed.get());
        }
    }

    @Test
    void startAndPipeFailuresAreTypedAndDoNotLeakExceptionMessages() {
        var scheduler = new ManualTaskScheduler();
        try (var runner = runner(request -> {
            throw new IOException("/private/path secret-command");
        }, scheduler)) {
            var result = runner.run(REQUEST, (channel, bytes, offset, length) -> { },
                    new MutableCancellationSignal());
            assertEquals(ProcessRunResult.Termination.START_FAILED, result.termination());
            assertFalse(result.diagnostic().orElse("").contains("private"));
            assertFalse(result.toString().contains("secret-command"));
        }

        var failedStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("pipe failed");
            }
        };
        var process = FakeManagedProcess.exited(0, failedStream);
        try (var runner = runner(request -> process, new ManualTaskScheduler())) {
            var result = runner.run(REQUEST, (channel, bytes, offset, length) -> { },
                    new MutableCancellationSignal());
            assertEquals(ProcessRunResult.Termination.FAILED, result.termination());
            assertEquals("output collection failed", result.diagnostic().orElseThrow());
        }
    }

    @Test
    void exhaustedCapacityFailsFastWithoutLaunching() throws Exception {
        var first = new FakeManagedProcess(new ByteArrayInputStream(new byte[0]));
        var secondLaunch = new AtomicBoolean();
        ProcessLauncher launcher = request -> {
            if (secondLaunch.getAndSet(true)) {
                fail("second process must not be launched");
            }
            return first;
        };
        var scheduler = new ManualTaskScheduler();

        try (var runner = runner(launcher, scheduler, 1);
             var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstResult = CompletableFuture.supplyAsync(
                    () -> runner.run(REQUEST, (channel, bytes, offset, length) -> { },
                            new MutableCancellationSignal()), caller);
            scheduler.awaitTask();

            var second = runner.run(REQUEST, (channel, bytes, offset, length) -> { },
                    new MutableCancellationSignal());
            assertEquals(ProcessRunResult.Termination.RESOURCE_BUSY, second.termination());

            first.completeExit(0);
            assertEquals(ProcessRunResult.Termination.EXITED,
                    firstResult.get(5, TimeUnit.SECONDS).termination());
        }
    }

    private static ProcessRunner runner(ProcessLauncher launcher, ManualTaskScheduler scheduler) {
        return runner(launcher, scheduler, 4);
    }

    private static ProcessRunner runner(
            ProcessLauncher launcher,
            ManualTaskScheduler scheduler,
            int capacity
    ) {
        return new ProcessRunner(launcher, scheduler,
                Executors.newVirtualThreadPerTaskExecutor(), capacity, true);
    }

    private static final class FakeManagedProcess implements ManagedProcess {
        private final InputStream output;
        private final InputStream error;
        private final CompletableFuture<Integer> exitCode = new CompletableFuture<>();
        private final AtomicBoolean gracefulTermination = new AtomicBoolean();
        private final AtomicBoolean forcefulTermination = new AtomicBoolean();
        private final CountDownLatch terminationRequested = new CountDownLatch(1);
        private final CountDownLatch outputRequested = new CountDownLatch(1);
        private boolean ignoreGraceful;
        private boolean ignoreForceful;
        private boolean hasResistantChild;
        private final CompletableFuture<Void> childExit = new CompletableFuture<>();

        private FakeManagedProcess(InputStream output) {
            this(output, InputStream.nullInputStream());
        }

        private FakeManagedProcess(InputStream output, InputStream error) {
            this.output = output;
            this.error = error;
        }

        static FakeManagedProcess exited(int code, String output) {
            return exited(code, new ByteArrayInputStream(
                    output.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        static FakeManagedProcess exited(int code, InputStream output) {
            return exited(code, output, InputStream.nullInputStream());
        }

        static FakeManagedProcess exited(int code, InputStream output, InputStream error) {
            var process = new FakeManagedProcess(output, error);
            process.completeExit(code);
            return process;
        }

        void completeExit(int code) {
            exitCode.complete(code);
        }

        @Override
        public InputStream standardOutput() {
            outputRequested.countDown();
            return output;
        }

        @Override
        public InputStream standardError() {
            return error;
        }

        @Override
        public CompletableFuture<Integer> exitCode() {
            return exitCode;
        }

        @Override
        public CompletionStage<Void> terminateTree(boolean force) {
            if (force) {
                forcefulTermination.set(true);
                if (!ignoreForceful) {
                    exitCode.complete(-1);
                    childExit.complete(null);
                }
            } else {
                gracefulTermination.set(true);
                if (!ignoreGraceful) {
                    exitCode.complete(-1);
                }
            }
            terminationRequested.countDown();
            return hasResistantChild
                    ? CompletableFuture.allOf(exitCode, childExit)
                    : exitCode.thenApply(ignored -> null);
        }

        @Override
        public void closeOutput() throws IOException {
            output.close();
            error.close();
        }
    }

    private static final class CloseReleasedInputStream extends InputStream {
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            readEntered.countDown();
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            return -1;
        }

        @Override
        public void close() {
            closed.set(true);
            released.countDown();
        }
    }

    private static final class CopyOnWriteChunkList {
        private final List<String> values = new java.util.concurrent.CopyOnWriteArrayList<>();

        void add(ProcessOutputChannel channel, byte[] bytes, int offset, int length) {
            values.add(channel + ":" + new String(
                    bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static final class ManualTaskScheduler implements TaskScheduler {
        private final Object lock = new Object();
        private final ArrayDeque<Entry> entries = new ArrayDeque<>();
        private boolean closed;

        @Override
        public ScheduledTask schedule(Duration delay, Runnable task) {
            var entry = new Entry(task);
            synchronized (lock) {
                if (closed) {
                    throw new IllegalStateException("scheduler is closed");
                }
                entries.add(entry);
                lock.notifyAll();
            }
            return () -> {
                synchronized (lock) {
                    entry.cancelled = true;
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
