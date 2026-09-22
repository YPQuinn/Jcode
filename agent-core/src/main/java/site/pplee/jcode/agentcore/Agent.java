package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.queue.PendingMessageQueue;
import site.pplee.jcode.agentcore.queue.QueueMode;

import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 * shutdown. A synchronous completion callback may close the agent from its run
 * worker; that path starts executor shutdown without waiting for itself.
 */
public final class Agent implements AutoCloseable {
    private final AgentConfig config;
    private final ExecutorService executor;
    private final AgentLoop loop;
    private final PendingMessageQueue steeringQueue;
    private final PendingMessageQueue followUpQueue;
    private final Object admissionLock = new Object();
    private final AtomicReference<ActiveRun> activeRun = new AtomicReference<>(null);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    // Completion callbacks run inline before the per-task virtual thread returns.
    private final ThreadLocal<Boolean> executorWorker = new ThreadLocal<>();
    private volatile AgentContext context;
    private ModelRef model;
    private ThinkingLevel thinkingLevel;
    private final AtomicReference<AgentState> state;

    public Agent(AgentConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.loop = new AgentLoop(this.executor);
        this.steeringQueue = new PendingMessageQueue(this.config.steeringMode());
        this.followUpQueue = new PendingMessageQueue(this.config.followUpMode());
        this.context = this.config.initialContext();
        this.model = this.config.model();
        this.thinkingLevel = this.config.thinkingLevel();
        this.state = new AtomicReference<>(AgentState.initial(this.context));
    }

    /** Start a new run with a user message; fails if a run is already active. */
    public CompletionStage<LoopResult> prompt(Message.User message) {
        Objects.requireNonNull(message, "message must not be null");
        return submit(message, ContinueMode.NONE);
    }

    /** Resume the loop from the current context; fails if the last message is an assistant. */
    public CompletionStage<LoopResult> continueRun() {
        return submit(null, ContinueMode.NORMAL);
    }

    /**
     * Resume after a terminal assistant model failure without adding another
     * user message. This narrow entry point accepts only an {@code ERROR}
     * assistant leaf; callers remain responsible for deciding whether the
     * classified failure is recoverable.
     */
    public CompletionStage<LoopResult> continueAfterFailure() {
        return submit(null, ContinueMode.AFTER_FAILURE);
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
        synchronized (admissionLock) {
            return context;
        }
    }

    /** Real-time agent state snapshot; updated before each event reaches the user sink. */
    public AgentState state() {
        synchronized (admissionLock) {
            return state.get();
        }
    }

    /** True while a run is active. */
    public boolean isRunning() {
        return activeRun.get() != null;
    }

    /**
     * Replace the transcript while this agent is idle.
     *
     * <p>The system prompt, registered tool instances, pending steering and
     * follow-up queues, and latest error are retained. The context and public
     * state snapshots are updated within the same admission boundary, so a
     * newly admitted run cannot observe a partial update. This method does not
     * invoke model, tool, hook, transformer, projector, or event-sink code.
     */
    public void replaceMessages(List<AgentMessage> messages) {
        var replacement = List.copyOf(
                Objects.requireNonNull(messages, "messages must not be null"));
        synchronized (admissionLock) {
            requireIdle();
            var current = context;
            var updated = new AgentContext(current.systemPrompt(), replacement, current.tools());
            context = updated;
            state.updateAndGet(snapshot -> new AgentState(
                    updated, false, null, Set.of(), snapshot.errorMessage()));
        }
    }

    /**
     * Replace only the system prompt while this agent is idle.
     *
     * <p>The transcript and registered tool instances are retained. The
     * context and public state snapshots are updated within the same admission
     * boundary, so a newly admitted run cannot observe a partial update.
     */
    public void updateSystemPrompt(String systemPrompt) {
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        synchronized (admissionLock) {
            requireIdle();
            var current = context;
            var updated = new AgentContext(systemPrompt, current.messages(), current.tools());
            context = updated;
            state.updateAndGet(snapshot -> new AgentState(updated, false, null, Set.of(), snapshot.errorMessage()));
        }
    }

    /**
     * Atomically replace the model and thinking level while this agent is idle.
     * A newly admitted run snapshots the pair together and keeps it for that run.
     */
    public void updateModel(ModelRef model, ThinkingLevel thinkingLevel) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(thinkingLevel, "thinkingLevel must not be null");
        synchronized (admissionLock) {
            requireIdle();
            this.model = model;
            this.thinkingLevel = thinkingLevel;
        }
    }

    /** Current model used for the next admitted run. */
    public ModelRef model() {
        synchronized (admissionLock) {
            return model;
        }
    }

    /** Current thinking level used for the next admitted run. */
    public ThinkingLevel thinkingLevel() {
        synchronized (admissionLock) {
            return thinkingLevel;
        }
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
        synchronized (admissionLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        var run = activeRun.get();
        if (run != null) {
            run.source().cancel();
        }
        executor.shutdown();
        if (Boolean.TRUE.equals(executorWorker.get())) {
            return;
        }
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

    private CompletableFuture<LoopResult> submit(Message.User message, ContinueMode continueMode) {
        var source = new CancellationSource();
        var future = new CompletableFuture<LoopResult>();
        var run = new ActiveRun(source, future);
        AgentContext snapshot;
        AgentLoopConfig loopConfig;
        synchronized (admissionLock) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Agent is closed"));
            }
            if (!activeRun.compareAndSet(null, run)) {
                future.completeExceptionally(new IllegalStateException("Agent is already running"));
                return future;
            }
            snapshot = context;
            loopConfig = getLoopConfig();
        }
        if (continueMode != ContinueMode.NONE) {
            if (snapshot.messages().isEmpty()) {
                synchronized (admissionLock) {
                    activeRun.compareAndSet(run, null);
                }
                future.completeExceptionally(new IllegalStateException("no messages to continue from"));
                return future;
            }
            var last = snapshot.messages().getLast();
            if (continueMode == ContinueMode.NORMAL && isStandardAssistant(last)) {
                synchronized (admissionLock) {
                    activeRun.compareAndSet(run, null);
                }
                future.completeExceptionally(new IllegalStateException("last message is assistant; use prompt() instead"));
                return future;
            }
            if (continueMode == ContinueMode.AFTER_FAILURE && !isErrorAssistant(last)) {
                synchronized (admissionLock) {
                    activeRun.compareAndSet(run, null);
                }
                future.completeExceptionally(new IllegalStateException(
                        "last message must be an assistant ERROR"));
                return future;
            }
        }
        try {
            executor.execute(() -> {
                executorWorker.set(true);
                LoopResult result = null;
                Throwable failure = null;
                try {
                    result = continueMode != ContinueMode.NONE
                            ? loop.continueRun(snapshot, loopConfig, source.signal())
                            : loop.runPrompt(List.of(StandardAgentMessage.of(message)), snapshot, loopConfig, source.signal());
                    // Publish the successful context before releasing admission.
                    synchronized (admissionLock) {
                        context = result.context();
                    }
                } catch (Throwable t) {
                    failure = t;
                    // Stop in-flight provider work without masking the original run failure.
                    try {
                        source.cancel();
                    } catch (Throwable cancellationFailure) {
                        if (cancellationFailure != t) {
                            t.addSuppressed(cancellationFailure);
                        }
                    }
                }
                // Reset streaming state: streaming=false, clear streamingMessage/pendingToolCalls.
                // Preserve errorMessage from the reducer on success; clear on failure.
                final Throwable runFailure = failure;
                state.updateAndGet(current -> new AgentState(context, false, null, Set.of(),
                        runFailure == null ? current.errorMessage() : null));
                // clear ref → complete future. isRunning() flips to false before the
                // caller's thenAccept fires.
                synchronized (admissionLock) {
                    activeRun.compareAndSet(run, null);
                }
                if (failure != null) {
                    future.completeExceptionally(failure);
                } else {
                    future.complete(result);
                }
                executorWorker.remove();
            });
        } catch (RejectedExecutionException rej) {
            // close() shut down the executor between CAS and execute; the task never
            // runs, so its finally never fires. Clean up here; do NOT synthesize an
            // ABORTED assistant — the loop never ran, so context stays unchanged.
            synchronized (admissionLock) {
                activeRun.compareAndSet(run, null);
            }
            future.completeExceptionally(new IllegalStateException("Agent is closed", rej));
        }
        return future;
    }

    private static boolean isErrorAssistant(AgentMessage message) {
        return message instanceof StandardAgentMessage standard
                && standard.message() instanceof Message.Assistant assistant
                && assistant.stopReason() == StopReason.ERROR;
    }

    private enum ContinueMode {
        NONE,
        NORMAL,
        AFTER_FAILURE
    }

    private void requireIdle() {
        if (closed.get()) {
            throw new IllegalStateException("Agent is closed");
        }
        if (activeRun.get() != null) {
            throw new IllegalStateException("Agent is already running");
        }
    }

    private AgentLoopConfig getLoopConfig() {
        var reducerSink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                reduceState(event);
                return config.eventSink().emit(event);
            }
        };
        return new AgentLoopConfig(
                model,
                config.modelClient(),
                config.objectMapper(),
                config.contextTransformer(),
                config.messageProjector(),
                config.toolExecution(),
                config.beforeToolCall(),
                config.afterToolCall(),
                steeringQueue,
                followUpQueue,
                new RunEventEmitter(reducerSink),
                thinkingLevel,
                config.prepareNextTurn(),
                config.shouldStopAfterTurn(),
                config.modelRequestOptions()
        );
    }

    /**
     * Event reducer: update {@link #state} based on the event, BEFORE the
     * user's sink sees it. Called by the internal reducer sink wrapper.
     *
     * <p>The reduction is a CAS loop over the immutable {@link AgentState}
     * snapshot: tool worker threads reduce {@code ToolUpdate} events
     * concurrently with the loop thread reducing lifecycle events, and the
     * atomic update prevents lost pending-tool-call updates. Only the
     * reduction is atomic; the user sink is invoked after the CAS settles and
     * never while holding a lock.
     */
    private void reduceState(AgentEvent event) {
        state.updateAndGet(current -> reduce(current, event));
    }

    private static AgentState reduce(AgentState current, AgentEvent event) {
        return switch (event) {
            case AgentEvent.AgentStarted ignored -> new AgentState(
                    current.context(), true, null, Set.of(), null);
            case AgentEvent.TurnStarted ignored -> current;
            case AgentEvent.MessageStarted m -> new AgentState(
                    current.context(), current.streaming(),
                    m.message(), current.pendingToolCalls(), current.errorMessage());
            case AgentEvent.MessageUpdated u -> new AgentState(
                    current.context(), current.streaming(),
                    u.message(), current.pendingToolCalls(), current.errorMessage());
            case AgentEvent.MessageCompleted ignored -> new AgentState(
                    current.context(), current.streaming(),
                    null, current.pendingToolCalls(), current.errorMessage());
            case AgentEvent.ToolStarted t -> {
                var pending = new HashSet<>(current.pendingToolCalls());
                pending.add(t.call().id());
                yield new AgentState(current.context(), current.streaming(),
                        current.streamingMessage(), Set.copyOf(pending), current.errorMessage());
            }
            case AgentEvent.ToolUpdate ignored -> current;
            case AgentEvent.ToolCompleted tc -> {
                var pending = new HashSet<>(current.pendingToolCalls());
                pending.remove(tc.result().toolCallId());
                yield new AgentState(current.context(), current.streaming(),
                        current.streamingMessage(), Set.copyOf(pending), current.errorMessage());
            }
            case AgentEvent.TurnCompleted tc -> new AgentState(
                    current.context(), current.streaming(),
                    current.streamingMessage(), current.pendingToolCalls(),
                    tc.assistant().errorMessage());
            case AgentEvent.AgentCompleted ac -> new AgentState(
                    ac.result().context(), false, null, Set.of(), current.errorMessage());
        };
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
