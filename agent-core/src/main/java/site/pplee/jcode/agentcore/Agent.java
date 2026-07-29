package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.queue.PendingMessageQueue;
import site.pplee.jcode.agentcore.queue.QueueMode;

import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Message;

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
 * executor. If a provider ignores the {@link CancellationSignal} and blocks
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

    /** Start a new run with a user message; fails if a run is already active. */
    public CompletionStage<LoopResult> prompt(Message.User message) {
        Objects.requireNonNull(message, "message must not be null");
        return submit(message, false);
    }

    /** Resume the loop from the current context; fails if the last message is an assistant. */
    public CompletionStage<LoopResult> continueRun() {
        return submit(null, true);
    }

    /** Enqueue a steering message injected before the next model call of the active run. */
    public void steer(Message.User message) {
        steeringQueue.enqueue(StandardAgentMessage.of(message));
    }

    /** Enqueue a follow-up message injected only when the run would otherwise stop. */
    public void followUp(Message.User message) {
        followUpQueue.enqueue(StandardAgentMessage.of(message));
    }

    /** Request cancellation of the active run, if any; idempotent. */
    public void abort() {
        var run = activeRun.get();
        if (run != null) {
            run.source().cancel();
        }
    }

    /** Current context; replaced atomically after each successful run. */
    public AgentContext context() {
        return context;
    }

    /** True while a run is active. */
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

    private CompletableFuture<LoopResult> submit(Message.User message, boolean isContinue) {
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
            if (isStandardAssistant(last)) {
                activeRun.compareAndSet(run, null);
                future.completeExceptionally(new IllegalStateException("last message is assistant; use prompt() instead"));
                return future;
            }
        }        var loopConfig = new AgentLoopConfig(
                config.model(),
                config.modelClient(),
                config.objectMapper(),
                config.toolExecution(),
                config.beforeToolCall(),
                config.afterToolCall(),
                steeringQueue,
                followUpQueue,
                config.eventSink()
        );
        try {
            executor.execute(() -> {
                LoopResult result = null;
                Throwable failure = null;
                try {
                    result = isContinue
                            ? loop.continueRun(snapshot, loopConfig, source.signal())
                            : loop.runPrompt(List.of(StandardAgentMessage.of(message)), snapshot, loopConfig, source.signal());
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

    /**
     * A "last message is assistant" check at the open-transcript level: a
     * {@link site.pplee.jcode.agentcore.message.StandardAgentMessage} wrapping
     * an {@code ai.Message.Assistant}.
     */
    private static boolean isStandardAssistant(AgentMessage message) {
        return message instanceof StandardAgentMessage sam
                && sam.message() instanceof site.pplee.jcode.ai.message.Message.Assistant;
    }

    private record ActiveRun(CancellationSource source, CompletableFuture<LoopResult> future) {}
}
