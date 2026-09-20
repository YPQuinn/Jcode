package site.pplee.jcode.codingagent.tool;

import site.pplee.jcode.ai.concurrent.CancellationRegistration;
import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded local process runner with cancellation, timeout, and output-drain ownership. */
final class ProcessRunner implements ProcessExecutor {
    static final int DEFAULT_MAX_PROCESSES = 4;
    private static final int PIPE_BUFFER_BYTES = 16 * 1024;
    private static final Duration FORCE_DELAY = Duration.ofMillis(500);
    private static final Duration TERMINATION_LIMIT = Duration.ofSeconds(2);
    private static final Duration DRAIN_LIMIT = Duration.ofSeconds(1);

    private final ProcessLauncher launcher;
    private final TaskScheduler scheduler;
    private final ExecutorService executor;
    private final Semaphore permits;
    private final boolean ownsResources;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private final Set<Execution> active = ConcurrentHashMap.newKeySet();

    ProcessRunner() {
        this(new LocalProcessLauncher(), new ScheduledExecutorTaskScheduler(),
                Executors.newVirtualThreadPerTaskExecutor(), DEFAULT_MAX_PROCESSES, true);
    }

    ProcessRunner(
            ProcessLauncher launcher,
            TaskScheduler scheduler,
            ExecutorService executor,
            int maximumProcesses,
            boolean ownsResources
    ) {
        this.launcher = Objects.requireNonNull(launcher, "launcher must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        if (maximumProcesses < 1) {
            throw new IllegalArgumentException("maximumProcesses must be positive");
        }
        this.permits = new Semaphore(maximumProcesses);
        this.ownsResources = ownsResources;
    }

    @Override
    public ProcessRunResult run(
            ProcessRequest request,
            ProcessOutputConsumer output,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(output, "output must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        if (closed.get()) {
            return ProcessRunResult.failed("process runner is closed");
        }
        if (cancellation.isCancelled()) {
            return ProcessRunResult.cancelled();
        }
        if (!permits.tryAcquire()) {
            return ProcessRunResult.resourceBusy();
        }

        var execution = new Execution();
        synchronized (lifecycleLock) {
            if (closed.get()) {
                permits.release();
                return ProcessRunResult.failed("process runner is closed");
            }
            active.add(execution);
        }
        CancellationRegistration cancellationRegistration = () -> { };
        ScheduledTask deadline = () -> { };
        try {
            cancellationRegistration = cancellation.onCancellation(
                    () -> execution.requestStop(ProcessRunResult.Termination.CANCELLED));
            if (request.timeout() != null) {
                deadline = scheduler.schedule(request.timeout(),
                        () -> execution.requestStop(ProcessRunResult.Termination.TIMED_OUT));
            }
            if (execution.terminal.get() != null) {
                return execution.result();
            }

            final ManagedProcess process;
            try {
                process = launcher.start(request);
            } catch (IOException | RuntimeException e) {
                return execution.terminal.get() == null
                        ? ProcessRunResult.startFailed()
                        : execution.result();
            }

            var acceptingOutput = new AtomicBoolean(true);
            var pipeFailure = new AtomicReference<RuntimeException>();
            List<CompletableFuture<Void>> pumps;
            synchronized (lifecycleLock) {
                execution.attach(process);
                if (closed.get()) {
                    execution.forceNow();
                    acceptingOutput.set(false);
                    closeOutput(process);
                    return execution.awaitResult();
                }
                try {
                    pumps = startPumps(
                            process, request.outputMode(), output, acceptingOutput, pipeFailure);
                } catch (RuntimeException e) {
                    execution.forceNow();
                    acceptingOutput.set(false);
                    closeOutput(process);
                    return ProcessRunResult.failed("process execution failed");
                }
            }
            ProcessRunResult result = execution.awaitResult();
            deadline.cancel();
            boolean drained = drainOutput(process, pumps, acceptingOutput);
            RuntimeException collectionFailure = pipeFailure.get();
            if (result.termination() == ProcessRunResult.Termination.EXITED
                    && (!drained || collectionFailure != null)) {
                return ProcessRunResult.failed(!drained
                        ? "output drain did not complete"
                        : "output collection failed");
            }
            return result;
        } catch (RuntimeException e) {
            return ProcessRunResult.failed("process execution failed");
        } finally {
            deadline.cancel();
            cancellationRegistration.close();
            execution.cancelTimers();
            active.remove(execution);
            permits.release();
            closeOwnedResourcesIfIdle();
        }
    }

    @Override
    public void close() {
        List<Execution> executions;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            executions = List.copyOf(active);
        }
        for (Execution execution : executions) {
            execution.requestStop(ProcessRunResult.Termination.CANCELLED);
            execution.forceNow();
        }
        closeOwnedResourcesIfIdle();
    }

    /** Keep termination and drain deadlines alive until every admitted execution settles. */
    private void closeOwnedResourcesIfIdle() {
        if (ownsResources && closed.get() && active.isEmpty()
                && resourcesClosed.compareAndSet(false, true)) {
            scheduler.close();
            executor.shutdownNow();
        }
    }

    private static void closeOutput(ManagedProcess process) {
        try {
            process.closeOutput();
        } catch (IOException ignored) {
            // Process termination remains authoritative during exceptional cleanup.
        }
    }

    private List<CompletableFuture<Void>> startPumps(
            ManagedProcess process,
            ProcessRequest.OutputMode outputMode,
            ProcessOutputConsumer output,
            AtomicBoolean acceptingOutput,
            AtomicReference<RuntimeException> failure
    ) {
        var pumps = new ArrayList<CompletableFuture<Void>>();
        ProcessOutputChannel firstChannel = outputMode == ProcessRequest.OutputMode.MERGED
                ? ProcessOutputChannel.MERGED
                : ProcessOutputChannel.STANDARD_OUTPUT;
        pumps.add(pump(process.standardOutput(), firstChannel, output, acceptingOutput, failure));
        if (outputMode == ProcessRequest.OutputMode.SEPARATE) {
            pumps.add(pump(process.standardError(), ProcessOutputChannel.STANDARD_ERROR,
                    output, acceptingOutput, failure));
        }
        return List.copyOf(pumps);
    }

    private CompletableFuture<Void> pump(
            InputStream input,
            ProcessOutputChannel channel,
            ProcessOutputConsumer output,
            AtomicBoolean acceptingOutput,
            AtomicReference<RuntimeException> failure
    ) {
        var completion = new CompletableFuture<Void>();
        executor.execute(() -> {
            var buffer = new byte[PIPE_BUFFER_BYTES];
            try (input) {
                while (true) {
                    int read = input.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read > 0 && acceptingOutput.get()) {
                        output.accept(channel, buffer, 0, read);
                    }
                }
                completion.complete(null);
            } catch (IOException | RuntimeException e) {
                if (acceptingOutput.get()) {
                    failure.compareAndSet(null,
                            new RuntimeException("output collection failed"));
                }
                completion.complete(null);
            }
        });
        return completion;
    }

    private boolean drainOutput(
            ManagedProcess process,
            List<CompletableFuture<Void>> pumps,
            AtomicBoolean acceptingOutput
    ) {
        var allPumps = CompletableFuture.allOf(pumps.toArray(CompletableFuture[]::new));
        if (allPumps.isDone()) {
            allPumps.join();
            acceptingOutput.set(false);
            return true;
        }
        var drainExpired = new CompletableFuture<Void>();
        ScheduledTask drainDeadline = scheduler.schedule(DRAIN_LIMIT,
                () -> drainExpired.complete(null));
        try {
            CompletableFuture.anyOf(allPumps, drainExpired).join();
            if (allPumps.isDone()) {
                allPumps.join();
                acceptingOutput.set(false);
                return true;
            }
            acceptingOutput.set(false);
            try {
                process.closeOutput();
            } catch (IOException ignored) {
                // The bounded drain result remains authoritative.
            }
            return false;
        } finally {
            drainDeadline.cancel();
        }
    }

    private final class Execution {
        private final AtomicReference<ProcessRunResult.Termination> terminal =
                new AtomicReference<>();
        private final AtomicReference<ManagedProcess> process = new AtomicReference<>();
        private final AtomicBoolean terminationSubmitted = new AtomicBoolean();
        private final CompletableFuture<Void> settled = new CompletableFuture<>();
        private volatile Integer exitCode;
        private volatile ScheduledTask forceTask = () -> { };
        private volatile ScheduledTask exhaustedTask = () -> { };

        void attach(ManagedProcess attached) {
            process.set(attached);
            attached.exitCode().whenComplete((code, failure) -> {
                if (failure == null) {
                    if (terminal.compareAndSet(null, ProcessRunResult.Termination.EXITED)) {
                        exitCode = code;
                        settled.complete(null);
                    }
                } else if (terminal.compareAndSet(null, ProcessRunResult.Termination.FAILED)) {
                    settled.complete(null);
                }
                // A stopping execution settles on tree termination, not just the parent's exit.
            });
            if (terminal.get() == ProcessRunResult.Termination.CANCELLED
                    || terminal.get() == ProcessRunResult.Termination.TIMED_OUT) {
                if (closed.get()) {
                    forceNow();
                } else {
                    submitTermination();
                }
            }
        }

        void requestStop(ProcessRunResult.Termination reason) {
            if (terminal.compareAndSet(null, reason)) {
                submitTermination();
            }
        }

        private void submitTermination() {
            ManagedProcess current = process.get();
            if (current == null || !terminationSubmitted.compareAndSet(false, true)) {
                return;
            }
            try {
                forceTask = scheduler.schedule(FORCE_DELAY,
                        () -> executor.execute(() -> terminate(current, true)));
                exhaustedTask = scheduler.schedule(TERMINATION_LIMIT, this::terminationFailed);
                executor.execute(() -> terminate(current, false));
            } catch (RuntimeException e) {
                terminationFailed();
            }
        }

        private void terminate(ManagedProcess current, boolean force) {
            try {
                current.terminateTree(force).whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        settled.complete(null);
                    } else if (force) {
                        terminationFailed();
                    }
                });
            } catch (RuntimeException e) {
                if (force) {
                    terminationFailed();
                }
            }
        }

        private void terminationFailed() {
            if (!settled.isDone()) {
                terminal.set(ProcessRunResult.Termination.TERMINATION_FAILED);
                settled.complete(null);
            }
        }

        void forceNow() {
            ManagedProcess current = process.get();
            if (current != null) {
                if (terminationSubmitted.compareAndSet(false, true)) {
                    try {
                        exhaustedTask = scheduler.schedule(TERMINATION_LIMIT, this::terminationFailed);
                    } catch (RuntimeException e) {
                        terminationFailed();
                    }
                }
                terminate(current, true);
            }
        }

        ProcessRunResult awaitResult() {
            boolean interrupted = false;
            while (true) {
                try {
                    settled.toCompletableFuture().get();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                    requestStop(ProcessRunResult.Termination.CANCELLED);
                } catch (java.util.concurrent.ExecutionException e) {
                    terminal.set(ProcessRunResult.Termination.FAILED);
                    break;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return result();
        }

        ProcessRunResult result() {
            ProcessRunResult.Termination state = terminal.get();
            if (state == null) {
                return ProcessRunResult.failed("process execution failed");
            }
            return switch (state) {
                case EXITED -> ProcessRunResult.exited(exitCode == null ? -1 : exitCode);
                case TIMED_OUT -> ProcessRunResult.timedOut();
                case CANCELLED -> ProcessRunResult.cancelled();
                case TERMINATION_FAILED -> ProcessRunResult.terminationFailed();
                case START_FAILED -> ProcessRunResult.startFailed();
                case RESOURCE_BUSY -> ProcessRunResult.resourceBusy();
                case FAILED -> ProcessRunResult.failed("process execution failed");
            };
        }

        void cancelTimers() {
            forceTask.cancel();
            exhaustedTask.cancel();
        }
    }
}
