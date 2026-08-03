package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.ContextTransformer;
import site.pplee.jcode.agentcore.message.MessageProjector;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.queue.PendingMessageSource;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.turn.NextTurnUpdate;
import site.pplee.jcode.agentcore.turn.PrepareNextTurn;
import site.pplee.jcode.agentcore.turn.ShouldStopAfterTurn;
import site.pplee.jcode.agentcore.turn.TurnContext;
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

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Package-private agent loop. The only public run entry is {@code Agent}
 * (Step 8); this class is constructed by {@code Agent} and its methods are
 * invoked on {@code Agent}'s virtual-thread-per-task executor.
 * <p>
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
 * <p>Model-call seam: before each call the loop runs the two-stage context
 * projection pipeline — {@link ContextTransformer} (async, cancellation-aware)
 * produces a request-local {@link AgentMessage} view, then
 * {@link MessageProjector} (sync) projects that to {@link Message standard ai
 * messages} — and extracts {@link ToolSpec declarable specs} from
 * {@link AgentTool}s, then invokes the {@link ModelClient}. The returned
 * {@link AssistantMessageStream} is consumed via {@link #consumeStream}, which
 * emits {@code MessageStarted} on the initial {@code Start} event and
 * {@code MessageUpdated} for each delta. The final {@link Message.Assistant}
 * from {@code Done}/{@code Error} is wrapped back into the transcript as a
 * {@link StandardAgentMessage}.
 *
 * <p>Projection failures (transformer throw/exceptional stage, projector throw,
 * null or invalid output) are normalized into a terminal {@code ERROR} (or
 * {@code ABORTED} if cancelled) assistant message; the model is not called.
 * Cancellation is checked after transform and after project so a cancelled run
 * does not invoke the projector or model unnecessarily.
 *
 * <p>Next-turn control (Wave 4): after each normal turn's {@code TurnCompleted}
 * has been emitted and awaited, the loop runs {@link PrepareNextTurn}, applies
 * the returned update to the run-local state, then runs
 * {@link ShouldStopAfterTurn} before draining the steering/follow-up queues.
 * Hook failures are normalized into a synthetic terminal turn; terminal
 * assistant failures skip the hooks entirely.
 *
 * <p>Cancellation keeps the event stream well-formed: a cancellation observed
 * while a turn is still open completes that turn with the aborted assistant;
 * a cancellation observed after {@code TurnCompleted} opens a new synthetic
 * turn ({@code TurnStarted} first) so every {@code TurnCompleted} stays
 * paired with a preceding {@code TurnStarted}.
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

        var state = new LoopState(context, config.model(), config.thinkingLevel());
        var events = config.events();
        events.emit(new AgentEvent.AgentStarted());
        events.emit(new AgentEvent.TurnStarted());
        for (var p : prompts) {
            events.emit(new AgentEvent.MessageStarted(p));
            state.append(p);
            events.emit(new AgentEvent.MessageCompleted(p));
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

        var state = new LoopState(context, config.model(), config.thinkingLevel());
        var events = config.events();
        events.emit(new AgentEvent.AgentStarted());
        events.emit(new AgentEvent.TurnStarted());
        return runLoop(state, config, cancellation);
    }

    private LoopResult runLoop(LoopState state, AgentLoopConfig config, CancellationSignal cancellation) {
        boolean firstTurn = true;
        var pendingMessages = drainSteering(config);
        var events = config.events();

        while (true) {
            var hasMoreToolCalls = true;

            while (hasMoreToolCalls || !pendingMessages.isEmpty()) {
                // cancellation boundary 1: before next turn / before model call.
                // The first iteration still has the run-opening turn open; later
                // iterations sit between turns (previous TurnCompleted emitted).
                if (cancellation.isCancelled()) {
                    return firstTurn ? abortRun(state, events) : abortAfterTurn(state, events);
                }
                firstTurn = prepareTurn(state, events, pendingMessages, firstTurn);
                pendingMessages = List.of();

                var assistantMessage = invokeModelSafely(state, config, cancellation);
                recordAssistant(state, events, assistantMessage);

                if (assistantMessage.stopReason().isTerminalFailure()) {
                    return handleTerminalFailure(state, events, assistantMessage);
                }

                var toolCalls = extractToolCalls(assistantMessage);

                // cancellation boundary 3: before tool batch. The turn is still
                // open (TurnStarted emitted, no TurnCompleted yet), so the aborted
                // assistant completes the same turn without a new TurnStarted.
                if (!toolCalls.isEmpty() && cancellation.isCancelled()) {
                    return abortRun(state, events);
                }

                var outcomes = dispatchToolCalls(assistantMessage, toolCalls, state, config, cancellation, events);
                recordOutcomes(state, events, outcomes);
                events.emit(new AgentEvent.TurnCompleted(assistantMessage, toolResultMessagesOf(outcomes)));

                // Wave 4 next-turn control. Strict order after TurnCompleted:
                // PrepareNextTurn -> apply update -> ShouldStopAfterTurn
                // -> steering drain -> follow-up drain.
                if (cancellation.isCancelled()) {
                    return abortAfterTurn(state, events);
                }
                var prepareFailure = runPrepareNextTurn(state, config, cancellation,
                        assistantMessage, outcomes, events);
                if (prepareFailure != null) {
                    return prepareFailure;
                }
                var stopResult = runShouldStopAfterTurn(state, config, cancellation,
                        assistantMessage, outcomes, events);
                if (stopResult != null) {
                    return stopResult;
                }

                pendingMessages = drainSteering(config);
                hasMoreToolCalls = shouldContinueInnerLoop(outcomes);
            }

            // cancellation boundary: after the inner loop, before the run ends or
            // the next (follow-up) turn starts. Catches cancellation that arrived
            // during the tool batch (boundary 3 broke with empty or all-terminated
            // results, so the inner loop exited without hitting boundary 1 again).
            if (cancellation.isCancelled()) {
                return abortAfterTurn(state, events);
            }

            var followUp = drainFollowUp(config);
            if (followUp.isEmpty()) {
                break;
            }
            pendingMessages = followUp;
        }

        var result = state.result();
        events.emit(new AgentEvent.AgentCompleted(result));
        return result;
    }

    /**
     * Emit {@code TurnStarted} (except on the first turn) and append pending
     * steering messages. Returns the new {@code firstTurn} (always false).
     */
    private boolean prepareTurn(LoopState state, RunEventEmitter events,
                                 List<AgentMessage> pending, boolean firstTurn) {
        if (!firstTurn) {
            events.emit(new AgentEvent.TurnStarted());
        }
        for (var m : pending) {
            events.emit(new AgentEvent.MessageStarted(m));
            state.append(m);
            events.emit(new AgentEvent.MessageCompleted(m));
        }
        return false;
    }

    /**
     * Build the model request from the current context via the two-stage
     * projection pipeline, stream the response, and normalize failures into a
     * zero-usage {@link Message.Assistant}.
     *
     * <p>Pipeline: {@link ContextTransformer} (awaited) → copy/validate →
     * cancellation check → {@link MessageProjector} → copy/validate →
     * cancellation check → {@code ModelRequest} → {@code ModelClient.stream}.
     *
     * <p>Cancellation boundary 2 lives in the model-call phase: stream and
     * interruption errors are turned into an {@link StopReason#ABORTED} (if
     * cancelled) or {@link StopReason#ERROR} assistant message. Projection
     * failures follow the same normalization — the model is never called.
     */
    private Message.Assistant invokeModelSafely(LoopState state, AgentLoopConfig config,
                                                  CancellationSignal cancellation) {
        var messages = state.context().messages();
        var events = config.events();

        List<AgentMessage> transformed;
        try {
            var stage = Objects.requireNonNull(
                    config.contextTransformer().transform(messages, cancellation),
                    "context transformer returned null stage");
            transformed = stage.toCompletableFuture().join();
            transformed = List.copyOf(Objects.requireNonNull(
                    transformed, "context transformer returned null result"));
        } catch (RuntimeException e) {
            return projectionFailure(events, cancellation,
                    "context transformer failed: " + ToolCallExecutor.causeMessage(e));
        }

        if (cancellation.isCancelled()) {
            return projectionFailure(events, cancellation, "cancelled");
        }

        List<Message> projected;
        try {
            projected = List.copyOf(Objects.requireNonNull(
                    config.messageProjector().project(transformed),
                    "message projector returned null"));
        } catch (RuntimeException e) {
            return projectionFailure(events, cancellation,
                    "message projector failed: " + ToolCallExecutor.causeMessage(e));
        }

        if (cancellation.isCancelled()) {
            return projectionFailure(events, cancellation, "cancelled");
        }

        var request = new ModelRequest(
                state.model(),
                state.context().systemPrompt(),
                projected,
                toolSpecs(state.context().tools()),
                state.thinkingLevel()
        );
        try {
            var stream = config.modelClient().stream(request, cancellation);
            return consumeStream(stream, config.events());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cancellation.isCancelled() ? abortedAssistant("interrupted") : erroredAssistant("interrupted");
        } catch (RuntimeException e) {
            var msg = cancellation.isCancelled() ? "cancelled" : ToolCallExecutor.causeMessage(e);
            return cancellation.isCancelled() ? abortedAssistant(msg) : erroredAssistant(msg);
        }
    }

    /**
     * Emit {@code MessageStarted} for a projection-failure assistant (which
     * has no preceding stream {@code Start} event), then return it so the
     * caller's {@link #recordAssistant} can emit {@code MessageCompleted}.
     */
    private static Message.Assistant projectionFailure(
            RunEventEmitter events, CancellationSignal cancellation, String message
    ) {
        var failure = cancellation.isCancelled()
                ? abortedAssistant(message)
                : erroredAssistant(message);
        events.emit(new AgentEvent.MessageStarted(StandardAgentMessage.of(failure)));
        return failure;
    }

    /**
     * Wrap the assistant message, append it to the transcript, and emit
     * {@code MessageCompleted}. {@code MessageStarted} is not emitted here:
     * it was already produced by {@link #consumeStream} on the initial
     * {@code Start} event (or omitted on the failure path).
     */
    private void recordAssistant(LoopState state, RunEventEmitter events, Message.Assistant assistantMessage) {
        var assistant = StandardAgentMessage.of(assistantMessage);
        state.append(assistant);
        events.emit(new AgentEvent.MessageCompleted(assistant));
    }

    private LoopResult handleTerminalFailure(LoopState state, RunEventEmitter events,
                                              Message.Assistant assistantMessage) {
        events.emit(new AgentEvent.TurnCompleted(assistantMessage, List.of()));
        var result = state.result();
        events.emit(new AgentEvent.AgentCompleted(result));
        return result;
    }

    /**
     * Await {@link PrepareNextTurn} with the pre-update turn snapshot, validate
     * its stage/result, and atomically apply the returned update to the run
     * state. Returns {@code null} on success; on hook failure or cancellation
     * returns the terminating {@link LoopResult}.
     */
    private LoopResult runPrepareNextTurn(
            LoopState state, AgentLoopConfig config, CancellationSignal cancellation,
            Message.Assistant assistant, List<ToolCallExecutor.ToolOutcome> outcomes,
            RunEventEmitter events) {
        NextTurnUpdate update;
        try {
            var stage = Objects.requireNonNull(
                    config.prepareNextTurn().prepareNextTurn(
                            turnContext(assistant, outcomes, state), cancellation),
                    "prepareNextTurn returned null stage");
            update = stage.toCompletableFuture().join();
            Objects.requireNonNull(update, "prepareNextTurn returned null update");
        } catch (RuntimeException e) {
            return failRunAfterTurn(state, events, cancellation,
                    "prepareNextTurn failed: " + ToolCallExecutor.causeMessage(e));
        }
        if (cancellation.isCancelled()) {
            return abortAfterTurn(state, events);
        }
        state.applyUpdate(update);
        return null;
    }

    /**
     * Await {@link ShouldStopAfterTurn} with the post-update turn snapshot.
     * Returns {@code null} to continue; returns the final {@link LoopResult}
     * when the hook requests {@code STOP} or fails (normalized into a terminal
     * turn). Cancellation observed after the hook settles wins over
     * {@code STOP}: the run aborts instead of completing gracefully.
     */
    private LoopResult runShouldStopAfterTurn(
            LoopState state, AgentLoopConfig config, CancellationSignal cancellation,
            Message.Assistant assistant, List<ToolCallExecutor.ToolOutcome> outcomes,
            RunEventEmitter events) {
        ShouldStopAfterTurn.Decision decision;
        try {
            var stage = Objects.requireNonNull(
                    config.shouldStopAfterTurn().shouldStopAfterTurn(
                            turnContext(assistant, outcomes, state), cancellation),
                    "shouldStopAfterTurn returned null stage");
            decision = stage.toCompletableFuture().join();
            Objects.requireNonNull(decision, "shouldStopAfterTurn returned null decision");
        } catch (RuntimeException e) {
            return failRunAfterTurn(state, events, cancellation,
                    "shouldStopAfterTurn failed: " + ToolCallExecutor.causeMessage(e));
        }
        if (cancellation.isCancelled()) {
            return abortAfterTurn(state, events);
        }
        if (decision == ShouldStopAfterTurn.Decision.STOP) {
            var result = state.result();
            events.emit(new AgentEvent.AgentCompleted(result));
            return result;
        }
        return null;
    }

    /**
     * Terminate the run with a synthetic turn carrying a terminal assistant,
     * used when a post-turn control hook fails. The completed turn already
     * emitted {@code TurnCompleted}; this failure turn keeps the event stream a
     * valid lifecycle (TurnStarted -> MessageStarted -> MessageCompleted ->
     * TurnCompleted -> AgentCompleted). Steering/follow-up are not drained and
     * the run future completes normally.
     */
    private LoopResult failRunAfterTurn(LoopState state, RunEventEmitter events,
                                        CancellationSignal cancellation, String message) {
        var failure = cancellation.isCancelled() ? abortedAssistant(message) : erroredAssistant(message);
        var wrapped = StandardAgentMessage.of(failure);
        events.emit(new AgentEvent.TurnStarted());
        events.emit(new AgentEvent.MessageStarted(wrapped));
        state.append(wrapped);
        events.emit(new AgentEvent.MessageCompleted(wrapped));
        events.emit(new AgentEvent.TurnCompleted(failure, List.of()));
        var result = state.result();
        events.emit(new AgentEvent.AgentCompleted(result));
        return result;
    }

    /** Build the immutable turn snapshot handed to the turn hooks. */
    private static TurnContext turnContext(
            Message.Assistant assistant,
            List<ToolCallExecutor.ToolOutcome> outcomes,
            LoopState state) {
        return new TurnContext(
                assistant,
                toolResultMessagesOf(outcomes),
                state.context(),
                state.newMessages()
        );
    }

    private List<ToolCallExecutor.ToolOutcome> dispatchToolCalls(Message.Assistant assistant,
                                                 List<Content.ToolCall> toolCalls,
                                                 LoopState state, AgentLoopConfig config,
                                                 CancellationSignal cancellation,
                                                 RunEventEmitter events) {
        if (toolCalls.isEmpty()) {
            return List.of();
        }
        var exec = ToolCallExecutor.of(state, config, cancellation, executor);
        if (assistant.stopReason() == StopReason.LENGTH) {
            return exec.failTruncated(toolCalls);
        }
        return exec.runBatch(toolCalls);
    }

    private void recordOutcomes(LoopState state, RunEventEmitter events, List<ToolCallExecutor.ToolOutcome> outcomes) {
        for (var outcome : outcomes) {
            var wrapped = StandardAgentMessage.of(outcome.message());
            events.emit(new AgentEvent.MessageStarted(wrapped));
            state.append(wrapped);
            events.emit(new AgentEvent.MessageCompleted(wrapped));
        }
    }

    private static List<Message.ToolResultMessage> toolResultMessagesOf(List<ToolCallExecutor.ToolOutcome> outcomes) {
        return outcomes.stream().map(ToolCallExecutor.ToolOutcome::message).toList();
    }

    private static boolean shouldContinueInnerLoop(List<ToolCallExecutor.ToolOutcome> outcomes) {
        return !outcomes.isEmpty() && !ToolCallExecutor.allTerminated(outcomes);
    }

    private static List<AgentMessage> drainSteering(AgentLoopConfig config) {
        return drain(config.steeringMessages());
    }

    private static List<AgentMessage> drainFollowUp(AgentLoopConfig config) {
        return drain(config.followUpMessages());
    }

    /**
     * Abort while the current turn is still open ({@code TurnStarted} already
     * emitted, no {@code TurnCompleted} yet): the aborted assistant completes
     * the same turn, so no additional {@code TurnStarted} is emitted.
     */
    private LoopResult abortRun(LoopState state, RunEventEmitter events) {
        return abortRun(state, events, false);
    }

    /**
     * Abort between turns: the previous turn already emitted
     * {@code TurnCompleted}. A synthetic turn is opened with
     * {@code TurnStarted} so the aborted assistant keeps the
     * TurnStarted -> MessageStarted -> MessageCompleted -> TurnCompleted
     * pairing valid.
     */
    private LoopResult abortAfterTurn(LoopState state, RunEventEmitter events) {
        return abortRun(state, events, true);
    }

    private LoopResult abortRun(LoopState state, RunEventEmitter events, boolean openTurn) {
        var aborted = abortedAssistant("cancelled");
        var wrapped = StandardAgentMessage.of(aborted);
        if (openTurn) {
            events.emit(new AgentEvent.TurnStarted());
        }
        events.emit(new AgentEvent.MessageStarted(wrapped));
        state.append(wrapped);
        events.emit(new AgentEvent.MessageCompleted(wrapped));
        events.emit(new AgentEvent.TurnCompleted(aborted, List.of()));
        var result = state.result();
        events.emit(new AgentEvent.AgentCompleted(result));
        return result;
    }

    private static List<Content.ToolCall> extractToolCalls(Message.Assistant assistant) {
        return assistant.content().stream()
                .filter(Content.ToolCall.class::isInstance)
                .map(Content.ToolCall.class::cast)
                .toList();
    }

    private static List<AgentMessage> drain(PendingMessageSource source) {
        var drained = source.drain();
        return drained == null ? List.of() : drained;
    }

    private static Message.Assistant abortedAssistant(String msg) {
        return new Message.Assistant(List.of(), StopReason.ABORTED, msg, Usage.zero(), Instant.now());
    }

    private static Message.Assistant erroredAssistant(String msg) {
        return new Message.Assistant(List.of(), StopReason.ERROR, msg, Usage.zero(), Instant.now());
    }

    /**
     * Consume streaming events from the model, emitting MessageStarted on
     * the initial Start event and MessageUpdated for each delta. Returns the
     * final assistant message from the terminal Done or Error event.
     */
    private static Message.Assistant consumeStream(
            AssistantMessageStream stream, RunEventEmitter events
    ) throws InterruptedException {
        while (true) {
            var event = stream.take();
            switch (event) {
                case null -> {
                    return new Message.Assistant(List.of(), StopReason.ERROR,
                            "stream ended without terminal event", Usage.zero(), Instant.now());
                }
                case AssistantMessageEvent.Start(Message.Assistant partial) -> events.emit(new AgentEvent.MessageStarted(
                        StandardAgentMessage.of(partial)));
                case AssistantMessageEvent.Done d -> {
                    return d.message();
                }
                case AssistantMessageEvent.Error e -> {
                    return e.error();
                }
                default -> events.emit(new AgentEvent.MessageUpdated(
                        StandardAgentMessage.of(event.partial()), event));
            }
        }
    }

    private static List<ToolSpec> toolSpecs(List<AgentTool<?>> tools) {
        return tools.stream().map(AgentTool::spec).toList();
    }

}
