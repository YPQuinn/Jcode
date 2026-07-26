package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.model.AgentContext;
import site.pplee.jcode.agentcore.model.AgentMessage;
import site.pplee.jcode.agentcore.model.LoopResult;
import site.pplee.jcode.agentcore.queue.PendingMessageQueue;
import site.pplee.jcode.agentcore.queue.QueueMode;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public agent entry point. Wraps the package-private {@link AgentLoop} with
 * active-run protection, a virtual-thread-per-task executor, and steering /
 * follow-up queues. Implements {@link AutoCloseable}; callers must invoke
 * {@link #close()} to release the executor.
 *
 * <p>Each run constructs a fresh {@link AgentLoopConfig} and
 * {@link CancellationSource}; the loop runs synchronously on this agent's
 * executor and the returned {@link CompletionStage} completes after the run
 * settles.
 *
 * <p>Active-run invariant: at most one run is active at a time. Concurrent
 * calls to {@link #prompt} or {@link #continueRun} fail fast with an
 * exceptionally completed stage (no synchronous throw).
 *
 * <p>{@link #close()} cooperatively aborts an active run and drains the
 * executor. If a provider ignores the {@link CancellationSource} and blocks
 * uninterruptibly, close() may wait up to roughly seven seconds before forcing
 * shutdown.
 */
public final class Agent implements AutoCloseable {
    private final AgentConfig config;
    private final ExecutorService executor;
    private final AgentLoop loop;
    private final PendingMessageQueue steeringQueue;
    private final PendingMessageQueue followUpQueue;
    private final AtomicReference<ActiveRun> activeRun = new AtomicReference<>(null);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile AgentContext context;

    public Agent(AgentConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.loop = new AgentLoop(this.executor);
        this.steeringQueue = new PendingMessageQueue(this.config.steeringMode());
        this.followUpQueue = new PendingMessageQueue(this.config.followUpMode());
        this.context = this.config.initialContext();
    }

    public CompletionStage<LoopResult> prompt(AgentMessage.User message) {
        Objects.requireNonNull(message, "message must not be null");
        return submit(message, false);
    }

    public CompletionStage<LoopResult> continueRun() {
        return submit(null, true);
    }

    public void steer(AgentMessage.User message) {
        steeringQueue.enqueue(Objects.requireNonNull(message, "message must not be null"));
    }

    public void followUp(AgentMessage.User message) {
        followUpQueue.enqueue(Objects.requireNonNull(message, "message must not be null"));
    }

    public void abort() {
        var run = activeRun.get();
        if (run != null) {
            run.source().cancel();
        }
    }

    public AgentContext context() {
        return context;
    }

    public boolean isRunning() {
        return activeRun.get() != null;
    }

    public QueueMode steeringMode() {
        return steeringQueue.mode();
    }

    public void steeringMode(QueueMode mode) {
        steeringQueue.mode(Objects.requireNonNull(mode, "mode must not be null"));
    }

    public QueueMode followUpMode() {
        return followUpQueue.mode();
    }

    public void followUpMode(QueueMode mode) {
        followUpQueue.mode(Objects.requireNonNull(mode, "mode must not be null"));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        var run = activeRun.get();
        if (run != null) {
            run.source().cancel();
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private CompletableFuture<LoopResult> submit(AgentMessage.User message, boolean isContinue) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Agent is closed"));
        }
        var source = new CancellationSource();
        var future = new CompletableFuture<LoopResult>();
        var run = new ActiveRun(source, future);
        if (!activeRun.compareAndSet(null, run)) {
            future.completeExceptionally(new IllegalStateException("Agent is already running"));
            return future;
        }
        // CAS succeeded — we own the slot. Read snapshot AFTER CAS so a prior run's
        // context update (which happens before its ref clear) is visible.
        var snapshot = context;
        if (isContinue) {
            if (snapshot.messages().isEmpty()) {
                activeRun.compareAndSet(run, null);
                future.completeExceptionally(new IllegalStateException("no messages to continue from"));
                return future;
            }
            var last = snapshot.messages().get(snapshot.messages().size() - 1);
            if (last instanceof AgentMessage.Assistant) {
                activeRun.compareAndSet(run, null);
                future.completeExceptionally(new IllegalStateException("last message is assistant; use prompt() instead"));
                return future;
            }
        }
        var loopConfig = new AgentLoopConfig(
                config.model(),
                config.llmClient(),
                config.objectMapper(),
                config.toolExecution(),
                steeringQueue,
                followUpQueue,
                config.eventSink(),
                config.llmEventSink()
        );
        try {
            executor.execute(() -> {
                LoopResult result = null;
                Throwable failure = null;
                try {
                    result = isContinue
                            ? loop.continueRun(snapshot, loopConfig, source.token())
                            : loop.runPrompt(List.of(message), snapshot, loopConfig, source.token());
                    // Oracle F order: assign context (success only) before clearing ref,
                    // so a new run that CASes in after our clear sees the updated context.
                    context = result.context();
                } catch (Throwable t) {
                    failure = t;
                }
                // clear ref → complete future. isRunning() flips to false before the
                // caller's thenAccept fires.
                activeRun.compareAndSet(run, null);
                if (failure != null) {
                    future.completeExceptionally(failure);
                } else {
                    future.complete(result);
                }
            });
        } catch (RejectedExecutionException rej) {
            // close() shut down the executor between CAS and execute; the task never
            // runs, so its finally never fires. Clean up here; do NOT synthesize an
            // ABORTED assistant — the loop never ran, so context stays unchanged.
            activeRun.compareAndSet(run, null);
            future.completeExceptionally(new IllegalStateException("Agent is closed", rej));
        }
        return future;
    }

    private record ActiveRun(CancellationSource source, CompletableFuture<LoopResult> future) {}
}
