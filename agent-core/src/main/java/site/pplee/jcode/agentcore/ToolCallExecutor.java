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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
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
 * batch runs serially; otherwise in parallel. Results are always restored to
 * the source tool-call order before write-back.
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
            var outcome = runOne(tc);
            events.emit(new AgentEvent.ToolCompleted(outcome.message()));
            outcomes.add(outcome);
        }
        return List.copyOf(outcomes);
    }

    private List<ToolOutcome> runParallel(List<Content.ToolCall> calls) {
        var events = config.events();
        var futures = new ArrayList<CompletableFuture<ToolOutcome>>();
        for (var tc : calls) {
            if (cancellation.isCancelled()) {
                break;
            }
            events.emit(new AgentEvent.ToolStarted(tc));
            futures.add(CompletableFuture.supplyAsync(() -> runOne(tc), executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        var outcomes = new ArrayList<ToolOutcome>();
        for (CompletableFuture<ToolOutcome> future : futures) {
            var outcome = future.join();
            events.emit(new AgentEvent.ToolCompleted(outcome.message()));
            outcomes.add(outcome);
        }
        return List.copyOf(outcomes);
    }

    private ToolOutcome runOne(Content.ToolCall call) {
        var tool = toolMap.get(call.name());
        if (tool == null) {
            return ToolOutcome.failure(call, "tool not found: " + call.name());
        }
        return runTyped(tool, call);
    }

    private <A> ToolOutcome runTyped(AgentTool<A> tool, Content.ToolCall call) {
        // --- prepare ---
        JsonNode preparedArgs;
        try {
            preparedArgs = tool.prepareArguments(call.arguments());
        } catch (RuntimeException e) {
            return ToolOutcome.failure(call, "argument preparation failed: " + e.getMessage());
        }

        var schemaResult = ToolSchemaValidator.validate(preparedArgs, tool.parametersSchema());
        if (!schemaResult.valid()) {
            return ToolOutcome.failure(call, "schema validation failed: " + schemaResult.error());
        }

        var context = state.context();
        BeforeToolCall.Decision beforeDecision;
        try {
            beforeDecision = config.beforeToolCall()
                    .beforeToolCall(call, tool, preparedArgs, context, cancellation)
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException e) {
            return ToolOutcome.failure(call, "before hook failed: " + causeMessage(e));
        }
        if (beforeDecision instanceof BeforeToolCall.Decision.Block(String reason)) {
            return ToolOutcome.failure(call, "blocked by before hook: " + reason);
        }

        A args;
        try {
            args = config.objectMapper().treeToValue(preparedArgs, tool.argumentType());
        } catch (Exception e) {
            return ToolOutcome.failure(call, "argument conversion failed: " + e.getMessage());
        }

        // --- execute ---
        var updateSink = new LoopToolUpdateSink(call, config.events());
        ToolExecutionResult internal;
        try {
            internal = tool.execute(call.id(), args, updateSink, cancellation)
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException e) {
            internal = ToolExecutionResult.failure("tool execution failed: " + causeMessage(e));
        } catch (RuntimeException e) {
            internal = ToolExecutionResult.failure("tool execution failed: " + e.getMessage());
        }
        updateSink.settle();

        // --- finalize ---
        try {
            internal = config.afterToolCall()
                    .afterToolCall(call, tool, internal, context, cancellation)
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException e) {
            // after hook failure: keep the original result
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

    /**
     * Settle-aware update sink. Emits {@link AgentEvent.ToolUpdate} events
     * through the {@link RunEventEmitter}; after {@link #settle()} all further
     * updates are silently dropped.
     */
    private static final class LoopToolUpdateSink implements ToolUpdateSink {
        private final Content.ToolCall call;
        private final RunEventEmitter events;
        private volatile boolean settled;

        LoopToolUpdateSink(Content.ToolCall call, RunEventEmitter events) {
            this.call = call;
            this.events = events;
        }

        @Override
        public CompletionStage<Void> update(Content update) {
            if (settled) {
                return CompletableFuture.completedStage(null);
            }
            events.emit(new AgentEvent.ToolUpdate(call, update));
            return CompletableFuture.completedStage(null);
        }

        void settle() {
            settled = true;
        }
    }
}
