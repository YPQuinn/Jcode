package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.queue.PendingMessageSource;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.BeforeToolCall;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;

import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.ai.tool.ToolSpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;

/**
 * Package-private agent loop. The only public run entry is {@code Agent}
 * (Step 8); this class is constructed by {@code Agent} and its methods are
 * invoked on {@code Agent}'s virtual-thread-per-task executor.
 *
 * Implements the full 15-step {@code runLoop} sequence: model call, tool-call
 * extraction, three-phase tool execution (prepare / execute / finalize) with
 * sequential/parallel dispatch, ordered result write-back, LENGTH truncation
 * protection, steering/follow-up queues, and cancellation boundaries.
 *
 * <p>Tool pipeline (Wave 2):
 * <ul>
 *   <li><b>prepare</b>: find tool → prepareArguments → schema validate → beforeToolCall
 *   <li><b>execute</b>: tool.execute with ToolUpdateSink; exceptions → error result;
 *       sink settled after execute completes, ignoring late updates
 *   <li><b>finalize</b>: afterToolCall field-level patch → generate transcript message
 * </ul>
 *
 * <p>{@code terminate} is tracked on the runtime {@link ToolExecutionResult} only;
 * the standard {@link Message.ToolResultMessage} transcript carries no
 * terminate flag.
 *
 * <p>Model-call seam: before each call the loop projects the open
 * {@link AgentMessage} transcript to {@link Message standard ai messages}
 * (inline default projection — the seed of Wave 3's {@code MessageProjector})
 * and extracts {@link ToolSpec declarable specs} from {@link AgentTool}s, then
 * invokes the {@link ModelClient}. The returned {@link AssistantMessageStream}
 * is consumed via {@link #consumeStream}, which emits {@code MessageStarted}
 * on the initial {@code Start} event and {@code MessageUpdated} for each delta.
 * The final {@link Message.Assistant} from {@code Done}/{@code Error} is
 * wrapped back into the transcript as a {@link StandardAgentMessage}.
 */
final class AgentLoop {
    private final ExecutorService executor;

    AgentLoop(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    LoopResult runPrompt(
            List<AgentMessage> prompts,
            AgentContext context,
            AgentLoopConfig config,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(prompts, "prompts must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        for (var p : prompts) {
            Objects.requireNonNull(p, "prompts must not contain null");
        }

        var state = new LoopState(context);
        emit(new AgentEvent.AgentStarted(), config);
        emit(new AgentEvent.TurnStarted(), config);
        for (var p : prompts) {
            emit(new AgentEvent.MessageStarted(p), config);
            state.append(p);
            emit(new AgentEvent.MessageCompleted(p), config);
        }
        return runLoop(state, config, cancellation);
    }

    LoopResult continueRun(
            AgentContext context,
            AgentLoopConfig config,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");

        var state = new LoopState(context);
        emit(new AgentEvent.AgentStarted(), config);
        emit(new AgentEvent.TurnStarted(), config);
        return runLoop(state, config, cancellation);
    }

    private LoopResult runLoop(LoopState state, AgentLoopConfig config, CancellationSignal cancellation) {
        boolean firstTurn = true;
        var pendingMessages = drain(config.steeringMessages());

        while (true) {
            var hasMoreToolCalls = true;

            while (hasMoreToolCalls || !pendingMessages.isEmpty()) {
                // cancellation boundary 1: before next turn / before model call
                if (cancellation.isCancelled()) {
                    return abortRun(state, config);
                }
                if (!firstTurn) {
                    emit(new AgentEvent.TurnStarted(), config);
                }
                firstTurn = false;

                if (!pendingMessages.isEmpty()) {
                    for (var m : pendingMessages) {
                        emit(new AgentEvent.MessageStarted(m), config);
                        state.append(m);
                        emit(new AgentEvent.MessageCompleted(m), config);
                    }
                    pendingMessages = List.of();
                }

                // step 5: stream from model (cancellation boundary 2 handles stream errors)
                var request = new ModelRequest(
                        config.model(),
                        state.context().systemPrompt(),
                        projectMessages(state.context().messages()),
                        toolSpecs(state.context().tools())
                );
                Message.Assistant assistantMessage;
                try {
                    var stream = config.modelClient().stream(request, cancellation);
                    assistantMessage = consumeStream(stream, config);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    var reason = cancellation.isCancelled() ? StopReason.ABORTED : StopReason.ERROR;
                    assistantMessage = new Message.Assistant(
                            List.of(), reason, "interrupted", Usage.zero(), Instant.now());
                } catch (RuntimeException e) {
                    var reason = cancellation.isCancelled() ? StopReason.ABORTED : StopReason.ERROR;
                    var msg = cancellation.isCancelled() ? "cancelled" : causeMessage(e);
                    assistantMessage = new Message.Assistant(
                            List.of(), reason, msg, Usage.zero(), Instant.now());
                }
                var assistant = StandardAgentMessage.of(assistantMessage);

                // step 6: write assistant
                state.append(assistant);
                emit(new AgentEvent.MessageCompleted(assistant), config);

                // step 7: terminal failure -> end
                if (assistantMessage.stopReason().isTerminalFailure()) {
                    emit(new AgentEvent.TurnCompleted(assistantMessage, List.of()), config);
                    var result = state.result();
                    emit(new AgentEvent.AgentCompleted(result), config);
                    return result;
                }

                // step 8: extract tool calls
                var toolCalls = extractToolCalls(assistantMessage);

                // cancellation boundary 3: before tool batch
                if (!toolCalls.isEmpty() && cancellation.isCancelled()) {
                    return abortRun(state, config);
                }

                // step 9-10: execute tools (LENGTH -> fail all; otherwise dispatch)
                List<ToolOutcome> outcomes;
                if (toolCalls.isEmpty()) {
                    outcomes = List.of();
                } else if (assistantMessage.stopReason() == StopReason.LENGTH) {
                    outcomes = failTruncatedToolCalls(toolCalls, config);
                } else {
                    outcomes = executeToolCalls(toolCalls, state, config, cancellation);
                }
                for (var outcome : outcomes) {
                    var wrapped = StandardAgentMessage.of(outcome.message());
                    emit(new AgentEvent.MessageStarted(wrapped), config);
                    state.append(wrapped);
                    emit(new AgentEvent.MessageCompleted(wrapped), config);
                }

                // step 11: TurnCompleted
                var toolResultMessages = outcomes.stream()
                        .map(ToolOutcome::message)
                        .toList();
                emit(new AgentEvent.TurnCompleted(assistantMessage, toolResultMessages), config);

                // step 12: drain steering; decide inner loop continuation
                pendingMessages = drain(config.steeringMessages());
                hasMoreToolCalls = !outcomes.isEmpty() && !allTerminated(outcomes);
            }

            // cancellation boundary: after the inner loop, before the run ends or
            // the next (follow-up) turn starts. Catches cancellation that arrived
            // during the tool batch (boundary 4 broke with empty or all-terminated
            // results, so the inner loop exited without hitting boundary 1 again).
            if (cancellation.isCancelled()) {
                return abortRun(state, config);
            }

            // step 13: drain follow-up
            var followUp = drain(config.followUpMessages());
            if (!followUp.isEmpty()) {
                pendingMessages = followUp;
                continue;
            }
            break;
        }

        var result = state.result();
        emit(new AgentEvent.AgentCompleted(result), config);
        return result;
    }

    // --- three-phase tool execution ---

    private List<ToolOutcome> executeToolCalls(
            List<Content.ToolCall> toolCalls,
            LoopState state,
            AgentLoopConfig config,
            CancellationSignal cancellation
    ) {
        if (toolCalls.isEmpty()) {
            return List.of();
        }
        var toolMap = buildNameToTool(state.context().tools());
        boolean sequential = config.toolExecution() == ToolExecutionMode.SEQUENTIAL
                || toolCalls.stream().anyMatch(tc -> {
                    var t = toolMap.get(tc.name());
                    return t != null && t.executionMode() == ToolExecutionMode.SEQUENTIAL;
                });
        return sequential
                ? executeSequential(toolCalls, toolMap, state, config, cancellation)
                : executeParallel(toolCalls, toolMap, state, config, cancellation);
    }

    private List<ToolOutcome> executeSequential(
            List<Content.ToolCall> toolCalls,
            Map<String, AgentTool<?>> toolMap,
            LoopState state,
            AgentLoopConfig config,
            CancellationSignal cancellation
    ) {
        var outcomes = new ArrayList<ToolOutcome>();
        for (var tc : toolCalls) {
            if (cancellation.isCancelled()) {
                break;
            }
            emit(new AgentEvent.ToolStarted(tc), config);
            var outcome = executeOneToolCall(tc, toolMap, state, config, cancellation);
            emit(new AgentEvent.ToolCompleted(outcome.message()), config);
            outcomes.add(outcome);
        }
        return List.copyOf(outcomes);
    }

    private List<ToolOutcome> executeParallel(
            List<Content.ToolCall> toolCalls,
            Map<String, AgentTool<?>> toolMap,
            LoopState state,
            AgentLoopConfig config,
            CancellationSignal cancellation
    ) {
        var futures = new ArrayList<CompletableFuture<ToolOutcome>>();
        var submitted = new ArrayList<Content.ToolCall>();
        for (var tc : toolCalls) {
            if (cancellation.isCancelled()) {
                break;
            }
            emit(new AgentEvent.ToolStarted(tc), config);
            submitted.add(tc);
            futures.add(CompletableFuture.supplyAsync(
                    () -> executeOneToolCall(tc, toolMap, state, config, cancellation),
                    executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        var outcomes = new ArrayList<ToolOutcome>();
        for (int i = 0; i < futures.size(); i++) {
            var outcome = futures.get(i).join();
            emit(new AgentEvent.ToolCompleted(outcome.message()), config);
            outcomes.add(outcome);
        }
        return List.copyOf(outcomes);
    }

    private ToolOutcome executeOneToolCall(
            Content.ToolCall toolCall,
            Map<String, AgentTool<?>> toolMap,
            LoopState state,
            AgentLoopConfig config,
            CancellationSignal cancellation
    ) {
        var tool = toolMap.get(toolCall.name());
        if (tool == null) {
            var internal = ToolExecutionResult.failure("tool not found: " + toolCall.name());
            return new ToolOutcome(internal, toToolResultMessage(toolCall, internal));
        }
        return executeOneToolCallTyped(tool, toolCall, state, config, cancellation);
    }

    private <A> ToolOutcome executeOneToolCallTyped(
            AgentTool<A> tool,
            Content.ToolCall toolCall,
            LoopState state,
            AgentLoopConfig config,
            CancellationSignal cancellation
    ) {
        // --- prepare ---
        JsonNode preparedArgs;
        try {
            preparedArgs = tool.prepareArguments(toolCall.arguments());
        } catch (RuntimeException e) {
            var internal = ToolExecutionResult.failure("argument preparation failed: " + e.getMessage());
            return new ToolOutcome(internal, toToolResultMessage(toolCall, internal));
        }

        var schemaResult = ToolSchemaValidator.validate(preparedArgs, tool.parametersSchema());
        if (!schemaResult.valid()) {
            var internal = ToolExecutionResult.failure("schema validation failed: " + schemaResult.error());
            return new ToolOutcome(internal, toToolResultMessage(toolCall, internal));
        }

        var context = state.context();
        BeforeToolCall.Decision beforeDecision;
        try {
            beforeDecision = config.beforeToolCall()
                    .beforeToolCall(toolCall, tool, preparedArgs, context, cancellation)
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException e) {
            var internal = ToolExecutionResult.failure("before hook failed: " + causeMessage(e));
            return new ToolOutcome(internal, toToolResultMessage(toolCall, internal));
        }
        if (beforeDecision instanceof BeforeToolCall.Decision.Block block) {
            var internal = ToolExecutionResult.failure("blocked by before hook: " + block.reason());
            return new ToolOutcome(internal, toToolResultMessage(toolCall, internal));
        }

        A args;
        try {
            args = config.objectMapper().treeToValue(preparedArgs, tool.argumentType());
        } catch (Exception e) {
            var internal = ToolExecutionResult.failure("argument conversion failed: " + e.getMessage());
            return new ToolOutcome(internal, toToolResultMessage(toolCall, internal));
        }

        // --- execute ---
        var updateSink = new LoopToolUpdateSink(toolCall, config);
        ToolExecutionResult internal;
        try {
            internal = tool.execute(toolCall.id(), args, updateSink, cancellation)
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
            var patched = config.afterToolCall()
                    .afterToolCall(toolCall, tool, internal, context, cancellation)
                    .toCompletableFuture()
                    .join();
            internal = patched;
        } catch (CompletionException e) {
            // after hook failure: keep the original result
        }

        return new ToolOutcome(internal, toToolResultMessage(toolCall, internal));
    }

    private List<ToolOutcome> failTruncatedToolCalls(
            List<Content.ToolCall> toolCalls,
            AgentLoopConfig config
    ) {
        var outcomes = new ArrayList<ToolOutcome>();
        for (var tc : toolCalls) {
            emit(new AgentEvent.ToolStarted(tc), config);
            var internal = ToolExecutionResult.failure(
                    "tool call \"" + tc.name() + "\" not executed: "
                            + "response hit output token limit, arguments may be truncated");
            var msg = toToolResultMessage(tc, internal);
            emit(new AgentEvent.ToolCompleted(msg), config);
            outcomes.add(new ToolOutcome(internal, msg));
        }
        return List.copyOf(outcomes);
    }

    private LoopResult abortRun(LoopState state, AgentLoopConfig config) {
        var aborted = new Message.Assistant(
                List.of(), StopReason.ABORTED, "cancelled", Usage.zero(), Instant.now());
        var wrapped = StandardAgentMessage.of(aborted);
        emit(new AgentEvent.MessageStarted(wrapped), config);
        state.append(wrapped);
        emit(new AgentEvent.MessageCompleted(wrapped), config);
        emit(new AgentEvent.TurnCompleted(aborted, List.of()), config);
        var result = state.result();
        emit(new AgentEvent.AgentCompleted(result), config);
        return result;
    }

    private static Map<String, AgentTool<?>> buildNameToTool(List<AgentTool<?>> tools) {
        var map = new HashMap<String, AgentTool<?>>();
        for (var t : tools) {
            map.putIfAbsent(t.name(), t);
        }
        return map;
    }

    private static Message.ToolResultMessage toToolResultMessage(
            Content.ToolCall toolCall, ToolExecutionResult internal
    ) {
        return new Message.ToolResultMessage(
                toolCall.id(),
                toolCall.name(),
                internal.content(),
                internal.error(),
                Instant.now()
        );
    }

    private static boolean allTerminated(List<ToolOutcome> outcomes) {
        return outcomes.stream().allMatch(o -> o.internal().terminate());
    }

    private static List<Content.ToolCall> extractToolCalls(Message.Assistant assistant) {
        var calls = new ArrayList<Content.ToolCall>();
        for (var c : assistant.content()) {
            if (c instanceof Content.ToolCall tc) {
                calls.add(tc);
            }
        }
        return List.copyOf(calls);
    }

    private static List<AgentMessage> drain(PendingMessageSource source) {
        var drained = source.drain();
        return drained == null ? List.of() : drained;
    }

    private static String causeMessage(Throwable e) {
        var cause = e.getCause();
        var src = cause != null ? cause : e;
        var msg = src.getMessage();
        return msg != null ? msg : "unknown error";
    }

    /**
     * Consume streaming events from the model, emitting MessageStarted on
     * the initial Start event and MessageUpdated for each delta. Returns the
     * final assistant message from the terminal Done or Error event.
     */
    private static Message.Assistant consumeStream(
            AssistantMessageStream stream, AgentLoopConfig config
    ) throws InterruptedException {
        while (true) {
            var event = stream.take();
            if (event == null) {
                return new Message.Assistant(List.of(), StopReason.ERROR,
                        "stream ended without terminal event", Usage.zero(), Instant.now());
            }
            if (event instanceof AssistantMessageEvent.Start s) {
                emit(new AgentEvent.MessageStarted(
                        StandardAgentMessage.of(s.partial())), config);
            } else if (event instanceof AssistantMessageEvent.Done d) {
                return d.message();
            } else if (event instanceof AssistantMessageEvent.Error e) {
                return e.error();
            } else {
                emit(new AgentEvent.MessageUpdated(
                        StandardAgentMessage.of(event.partial()), event), config);
            }
        }
    }

    /**
     * Wave-0 default message projection (seed of Wave 3 {@code MessageProjector}):
     * unwrap {@link StandardAgentMessage} to {@link Message}; filter out unknown
     * product messages (keep user/assistant/toolResult, drop the rest).
     */
    private static List<Message> projectMessages(List<AgentMessage> messages) {
        var out = new ArrayList<Message>();
        for (var m : messages) {
            if (m instanceof StandardAgentMessage sam) {
                out.add(sam.message());
            }
            // unknown product messages are filtered out by the default projector
        }
        return List.copyOf(out);
    }

    private static List<ToolSpec> toolSpecs(List<AgentTool<?>> tools) {
        var out = new ArrayList<ToolSpec>(tools.size());
        for (var t : tools) {
            out.add(t.spec());
        }
        return List.copyOf(out);
    }

    private static void emit(AgentEvent event, AgentLoopConfig config) {
        config.eventSink().emit(event).toCompletableFuture().join();
    }

    /** Internal outcome: runtime result (with terminate) + transcript message (without). */
    private record ToolOutcome(ToolExecutionResult internal, Message.ToolResultMessage message) {}

    /**
     * Settle-aware update sink. Emits {@link AgentEvent.ToolUpdate} events
     * through the event sink; after {@link #settle()} all further updates are
     * silently dropped.
     */
    private static final class LoopToolUpdateSink implements ToolUpdateSink {
        private final Content.ToolCall call;
        private final AgentLoopConfig config;
        private volatile boolean settled;

        LoopToolUpdateSink(Content.ToolCall call, AgentLoopConfig config) {
            this.call = call;
            this.config = config;
        }

        @Override
        public CompletionStage<Void> update(Content update) {
            if (settled) {
                return CompletableFuture.completedStage(null);
            }
            config.eventSink().emit(new AgentEvent.ToolUpdate(call, update))
                    .toCompletableFuture()
                    .join();
            return CompletableFuture.completedStage(null);
        }

        void settle() {
            settled = true;
        }
    }
}
