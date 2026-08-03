package site.pplee.jcode.agentcore;

import com.fasterxml.jackson.databind.JsonNode;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.BeforeToolCall;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Package-private tool execution orchestrator. Owns the three-phase
 * prepare/execute/finalize pipeline and sequential/parallel dispatch.
 * Constructed per run via {@link #of}; holds no cross-run state.
 *
 * <p>Sequential vs parallel is decided per batch: if the run-level
 * {@link ToolExecutionMode} is {@link ToolExecutionMode#SEQUENTIAL}, or any
 * tool in the batch declares {@link ToolExecutionMode#SEQUENTIAL}, the whole
 * batch runs serially; otherwise in parallel.
 *
 * <p>Parallel batches keep two independent orders. The serial prepare pass
 * emits {@code ToolStarted} and runs the full prepare phase (find tool,
 * prepareArguments, schema validation, before hook, argument conversion) in
 * tool-call source order; prepare failures settle immediately as
 * {@code ToolCompleted} before the next {@code ToolStarted}. Prepared calls
 * then execute and finalize concurrently; their {@code ToolCompleted} events
 * are emitted by the run thread in actual completion order, while the
 * returned outcome list — and therefore the transcript write-back and turn
 * hook payloads — stays in source order.
 *
 * <p>Infrastructure failures (event delivery, executor rejection,
 * interruption) are never normalized into tool results. Once the first
 * failure is observed, no further lifecycle events are emitted and every
 * accepted task is drained before the first failure is rethrown;
 * prepared-but-unsubmitted entries are never executed.
 *
 * <p>{@code terminate} is tracked on the runtime {@link ToolExecutionResult}
 * only; the transcript {@link Message.ToolResultMessage} carries no
 * terminate flag.
 */
final class ToolCallExecutor {
    private final AgentLoopConfig config;
    private final LoopState state;
    private final CancellationSignal cancellation;
    private final ExecutorService executor;
    private final Map<String, AgentTool<?>> toolMap;

    private ToolCallExecutor(AgentLoopConfig config, LoopState state,
                             CancellationSignal cancellation, ExecutorService executor) {
        this.config = config;
        this.state = state;
        this.cancellation = cancellation;
        this.executor = executor;
        this.toolMap = buildNameToTool(state.context().tools());
    }

    static ToolCallExecutor of(LoopState state, AgentLoopConfig config,
                               CancellationSignal cancellation, ExecutorService executor) {
        return new ToolCallExecutor(config, state, cancellation, executor);
    }

    List<ToolOutcome> runBatch(List<Content.ToolCall> calls) {
        return shouldRunSequentially(calls) ? runSequential(calls) : runParallel(calls);
    }

    List<ToolOutcome> failTruncated(List<Content.ToolCall> calls) {
        var events = config.events();
        var outcomes = new ArrayList<ToolOutcome>();
        for (var tc : calls) {
            events.emit(new AgentEvent.ToolStarted(tc));
            var outcome = ToolOutcome.failure(tc,
                    "tool call \"" + tc.name() + "\" not executed: "
                            + "response hit output token limit, arguments may be truncated");
            events.emit(new AgentEvent.ToolCompleted(outcome.message()));
            outcomes.add(outcome);
        }
        return List.copyOf(outcomes);
    }

    private boolean shouldRunSequentially(List<Content.ToolCall> calls) {
        return config.toolExecution() == ToolExecutionMode.SEQUENTIAL
                || calls.stream().anyMatch(this::isSequentialTool);
    }

    private boolean isSequentialTool(Content.ToolCall tc) {
        var t = toolMap.get(tc.name());
        return t != null && t.executionMode() == ToolExecutionMode.SEQUENTIAL;
    }

    private List<ToolOutcome> runSequential(List<Content.ToolCall> calls) {
        var events = config.events();
        var outcomes = new ArrayList<ToolOutcome>();
        for (var tc : calls) {
            if (cancellation.isCancelled()) {
                break;
            }
            events.emit(new AgentEvent.ToolStarted(tc));
            var preparation = prepareCall(tc, outcomes.size());
            var outcome = (preparation instanceof ImmediateOutcome immediate)
                    ? immediate.outcome()
                    : executeAndFinalize((PreparedCall<?>) preparation);
            events.emit(new AgentEvent.ToolCompleted(outcome.message()));
            outcomes.add(outcome);
        }
        return List.copyOf(outcomes);
    }

    private List<ToolOutcome> runParallel(List<Content.ToolCall> calls) {
        var events = config.events();
        var preparations = new ArrayList<Preparation>();
        RuntimeException failure = null;

        // Serial prepare pass in source order. Immediate failures emit
        // ToolCompleted inline; a delivery/prepare infrastructure failure
        // stops the pass before any execute task is submitted.
        for (var tc : calls) {
            if (cancellation.isCancelled()) {
                break;
            }
            try {
                events.emit(new AgentEvent.ToolStarted(tc));
                var preparation = prepareCall(tc, preparations.size());
                if (preparation instanceof ImmediateOutcome immediate) {
                    events.emit(new AgentEvent.ToolCompleted(immediate.outcome().message()));
                }
                preparations.add(preparation);
            } catch (RuntimeException e) {
                failure = e;
                break;
            }
        }

        if (failure != null) {
            // No task was submitted, so nothing to drain; the batch fails
            // without fabricating tool results for calls that never executed.
            throw failure;
        }

        var slots = new ToolOutcome[preparations.size()];
        var completionService = new ExecutorCompletionService<IndexedOutcome>(executor);
        int submitted = 0;

        // Submit pass: only prepared calls are submitted; a rejection discards
        // the current and remaining entries (they never execute).
        for (var preparation : preparations) {
            if (preparation instanceof ImmediateOutcome immediate) {
                slots[immediate.index()] = immediate.outcome();
                continue;
            }
            var prepared = (PreparedCall<?>) preparation;
            try {
                completionService.submit(() ->
                        new IndexedOutcome(prepared.index(), executeAndFinalize(prepared)));
                submitted++;
            } catch (RejectedExecutionException e) {
                if (failure == null) {
                    failure = e;
                }
                break;
            }
        }

        // Drain pass in completion order: emit ToolCompleted for each settled
        // task unless a failure already occurred; keep settling the rest. An
        // interrupted take() consumes no completion, so the drain continues
        // until every submitted task has been consumed; the interrupt flag is
        // restored before the first failure is thrown.
        boolean interrupted = false;
        int drained = 0;
        while (drained < submitted) {
            IndexedOutcome completed;
            try {
                completed = completionService.take().get();
            } catch (InterruptedException e) {
                interrupted = true;
                if (failure == null) {
                    failure = new RuntimeException("interrupted while draining tool tasks", e);
                }
                continue;
            } catch (ExecutionException e) {
                if (failure == null) {
                    var cause = e.getCause();
                    failure = cause instanceof RuntimeException r ? r : new RuntimeException(cause);
                }
                drained++;
                continue;
            }
            drained++;
            slots[completed.index()] = completed.outcome();
            if (failure == null) {
                try {
                    events.emit(new AgentEvent.ToolCompleted(completed.outcome().message()));
                } catch (RuntimeException e) {
                    failure = e;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw failure;
        }

        var outcomes = new ArrayList<ToolOutcome>(slots.length);
        for (var slot : slots) {
            outcomes.add(slot);
        }
        return List.copyOf(outcomes);
    }

    private Preparation prepareCall(Content.ToolCall call, int index) {
        var tool = toolMap.get(call.name());
        if (tool == null) {
            return new ImmediateOutcome(index, ToolOutcome.failure(call, "tool not found: " + call.name()));
        }
        return prepareTyped(tool, call, index);
    }

    private <A> Preparation prepareTyped(AgentTool<A> tool, Content.ToolCall call, int index) {
        // --- prepare ---
        JsonNode preparedArgs;
        try {
            preparedArgs = tool.prepareArguments(call.arguments());
        } catch (RuntimeException e) {
            return new ImmediateOutcome(index,
                    ToolOutcome.failure(call, "argument preparation failed: " + e.getMessage()));
        }
        if (preparedArgs == null) {
            return new ImmediateOutcome(index,
                    ToolOutcome.failure(call, "argument preparation failed: null arguments"));
        }

        ToolSchemaValidator.Result schemaResult;
        try {
            schemaResult = ToolSchemaValidator.validate(preparedArgs, tool.parametersSchema());
        } catch (RuntimeException e) {
            // A tool-supplied schema that throws is a tool bug, not an
            // infrastructure failure: normalize it like any other prepare error.
            return new ImmediateOutcome(index,
                    ToolOutcome.failure(call, "schema validation failed: " + e.getMessage()));
        }
        if (!schemaResult.valid()) {
            return new ImmediateOutcome(index,
                    ToolOutcome.failure(call, "schema validation failed: " + schemaResult.error()));
        }

        var context = state.context();
        BeforeToolCall.Decision beforeDecision;
        try {
            var stage = config.beforeToolCall()
                    .beforeToolCall(call, tool, preparedArgs, context, cancellation);
            if (stage == null) {
                return new ImmediateOutcome(index,
                        ToolOutcome.failure(call, "before hook returned null stage"));
            }
            beforeDecision = stage.toCompletableFuture().join();
            if (beforeDecision == null) {
                return new ImmediateOutcome(index,
                        ToolOutcome.failure(call, "before hook returned null decision"));
            }
        } catch (CompletionException e) {
            return new ImmediateOutcome(index,
                    ToolOutcome.failure(call, "before hook failed: " + causeMessage(e)));
        } catch (RuntimeException e) {
            return new ImmediateOutcome(index,
                    ToolOutcome.failure(call, "before hook failed: " + e.getMessage()));
        }
        if (beforeDecision instanceof BeforeToolCall.Decision.Block(String reason)) {
            return new ImmediateOutcome(index,
                    ToolOutcome.failure(call, "blocked by before hook: " + reason));
        }

        A args;
        try {
            args = config.objectMapper().treeToValue(preparedArgs, tool.argumentType());
        } catch (Exception e) {
            return new ImmediateOutcome(index,
                    ToolOutcome.failure(call, "argument conversion failed: " + e.getMessage()));
        }
        return new PreparedCall<>(index, call, tool, args);
    }

    private <A> ToolOutcome executeAndFinalize(PreparedCall<A> prepared) {
        var call = prepared.call();
        var tool = prepared.tool();
        var updateSink = new LoopToolUpdateSink(call, config.events());

        // --- execute ---
        ToolExecutionResult internal;
        RuntimeException executeFailure = null;
        try {
            var stage = tool.execute(call.id(), prepared.args(), updateSink, cancellation);
            if (stage == null) {
                internal = ToolExecutionResult.failure("tool execution failed: null result");
            } else {
                internal = stage.toCompletableFuture().join();
                if (internal == null) {
                    internal = ToolExecutionResult.failure("tool execution failed: null result");
                }
            }
        } catch (EventDeliveryException e) {
            executeFailure = e;
            internal = null;
        } catch (CompletionException e) {
            if (isEventDeliveryFailure(e)) {
                executeFailure = e;
                internal = null;
            } else {
                internal = ToolExecutionResult.failure("tool execution failed: " + causeMessage(e));
            }
        } catch (RuntimeException e) {
            if (isEventDeliveryFailure(e)) {
                executeFailure = e;
                internal = null;
            } else {
                internal = ToolExecutionResult.failure("tool execution failed: " + e.getMessage());
            }
        }
        // Every exit path closes and drains the update sink: accepted updates
        // must settle before the call is considered complete. A recorded
        // delivery failure is rethrown here as an infrastructure failure and
        // never normalized into a tool result; finalize is skipped in that case.
        RuntimeException settleFailure;
        try {
            updateSink.settle();
            settleFailure = null;
        } catch (RuntimeException e) {
            settleFailure = e;
        }
        // The sink's recorded failure is the authoritative earliest
        // accepted-update delivery failure; when both paths failed, it wins
        // over a later execute failure that escaped synchronously.
        if (settleFailure != null) {
            throw settleFailure;
        }
        if (executeFailure != null) {
            throw executeFailure;
        }

        // --- finalize ---
        try {
            var stage = config.afterToolCall()
                    .afterToolCall(call, tool, internal, state.context(), cancellation);
            if (stage != null) {
                var patched = stage.toCompletableFuture().join();
                if (patched != null) {
                    internal = patched;
                }
            }
        } catch (CompletionException ignored) {
            // after hook failure: keep the original result
        } catch (RuntimeException ignored) {
            // after hook synchronous failure: keep the original result
        }

        return ToolOutcome.of(call, internal);
    }

    private static Map<String, AgentTool<?>> buildNameToTool(List<AgentTool<?>> tools) {
        return tools.stream().collect(Collectors.toMap(
                AgentTool::name, Function.identity(), (a, b) -> a));
    }

    static boolean allTerminated(List<ToolOutcome> outcomes) {
        return outcomes.stream().allMatch(o -> o.internal().terminate());
    }

    static String causeMessage(Throwable e) {
        var cause = e.getCause();
        var src = cause != null ? cause : e;
        var msg = src.getMessage();
        return msg != null ? msg : "unknown error";
    }

    /** True when the throwable chain carries an event delivery failure. */
    private static boolean isEventDeliveryFailure(Throwable e) {
        return e instanceof EventDeliveryException || e.getCause() instanceof EventDeliveryException;
    }

    private static Message.ToolResultMessage toToolResultMessage(
            Content.ToolCall call, ToolExecutionResult internal
    ) {
        return new Message.ToolResultMessage(
                call.id(),
                call.name(),
                internal.content(),
                internal.error(),
                Instant.now()
        );
    }

    /** Internal outcome: runtime result (with terminate) + transcript message (without). */
    record ToolOutcome(ToolExecutionResult internal, Message.ToolResultMessage message) {
        static ToolOutcome of(Content.ToolCall call, ToolExecutionResult result) {
            return new ToolOutcome(result, toToolResultMessage(call, result));
        }

        static ToolOutcome failure(Content.ToolCall call, String reason) {
            var result = ToolExecutionResult.failure(reason);
            return new ToolOutcome(result, toToolResultMessage(call, result));
        }
    }

    /** Result of the serial prepare phase for one tool call. */
    private sealed interface Preparation permits PreparedCall, ImmediateOutcome {
        /** Source index of the tool call within the batch. */
        int index();
    }

    /** Successfully prepared call, ready for parallel execute + finalize. */
    private record PreparedCall<A>(int index, Content.ToolCall call, AgentTool<A> tool, A args)
            implements Preparation {}

    /** Prepare-phase failure already settled into an outcome; no execution. */
    private record ImmediateOutcome(int index, ToolOutcome outcome) implements Preparation {}

    /** Completed task result carrying the source index for order restoration. */
    private record IndexedOutcome(int index, ToolOutcome outcome) {}

    /**
     * Per-tool update sink with a close-and-drain protocol. Updates are
     * accepted until {@link #settle()} closes the sink; accepted updates are
     * fully delivered (or failed) before settle returns, so no accepted update
     * outlives the tool call. Updates after settlement are silently dropped.
     * The event sink is never invoked while holding the internal lock.
     */
    private static final class LoopToolUpdateSink implements ToolUpdateSink {
        private final Content.ToolCall call;
        private final RunEventEmitter events;
        private final Object lock = new Object();
        private boolean accepting = true;
        private int inFlight;
        private RuntimeException firstFailure;

        LoopToolUpdateSink(Content.ToolCall call, RunEventEmitter events) {
            this.call = call;
            this.events = events;
        }

        @Override
        public CompletionStage<Void> update(Content update) {
            // A null update is a tool contract error and must surface as such
            // (normalized into an error result by the execute pipeline), not be
            // wrapped into an event delivery failure.
            Objects.requireNonNull(update, "update must not be null");
            synchronized (lock) {
                if (!accepting) {
                    return CompletableFuture.completedStage(null);
                }
                inFlight++;
            }
            try {
                events.emit(new AgentEvent.ToolUpdate(call, update));
                return CompletableFuture.completedStage(null);
            } catch (RuntimeException e) {
                var deliveryFailure = (e instanceof EventDeliveryException ede)
                        ? ede
                        : new EventDeliveryException("tool update delivery failed", e);
                synchronized (lock) {
                    if (firstFailure == null) {
                        firstFailure = deliveryFailure;
                    }
                }
                throw deliveryFailure;
            } finally {
                synchronized (lock) {
                    inFlight--;
                    lock.notifyAll();
                }
            }
        }

        /**
         * Close the sink and wait until all accepted updates have settled.
         * An interruption while waiting is recorded as the first failure, but
         * the drain continues; the interrupt flag is restored before the
         * failure is rethrown.
         */
        void settle() {
            RuntimeException failure;
            boolean interrupted = false;
            synchronized (lock) {
                accepting = false;
                while (inFlight > 0) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                        if (firstFailure == null) {
                            firstFailure =
                                    new EventDeliveryException("interrupted while draining tool updates", e);
                        }
                    }
                }
                failure = firstFailure;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
