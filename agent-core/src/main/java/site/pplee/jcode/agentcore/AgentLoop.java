package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.queue.PendingMessageSource;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
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

        var state = new LoopState(context);
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
                // cancellation boundary 1: before next turn / before model call
                if (cancellation.isCancelled()) {
                    return abortRun(state, events);
                }
                firstTurn = prepareTurn(state, events, pendingMessages, firstTurn);
                pendingMessages = List.of();

                var assistantMessage = invokeModelSafely(state, config, cancellation);
                recordAssistant(state, events, assistantMessage);

                if (assistantMessage.stopReason().isTerminalFailure()) {
                    return handleTerminalFailure(state, events, assistantMessage);
                }

                var toolCalls = extractToolCalls(assistantMessage);

                // cancellation boundary 3: before tool batch
                if (!toolCalls.isEmpty() && cancellation.isCancelled()) {
                    return abortRun(state, events);
                }

                var outcomes = dispatchToolCalls(assistantMessage, toolCalls, state, config, cancellation, events);
                recordOutcomes(state, events, outcomes);
                events.emit(new AgentEvent.TurnCompleted(assistantMessage, toolResultMessagesOf(outcomes)));

                pendingMessages = drainSteering(config);
                hasMoreToolCalls = shouldContinueInnerLoop(outcomes);
            }

            // cancellation boundary: after the inner loop, before the run ends or
            // the next (follow-up) turn starts. Catches cancellation that arrived
            // during the tool batch (boundary 3 broke with empty or all-terminated
            // results, so the inner loop exited without hitting boundary 1 again).
            if (cancellation.isCancelled()) {
                return abortRun(state, events);
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
     * Build the model request from the current context, stream the response,
     * and normalize failures into a zero-usage {@link Message.Assistant}.
     * Cancellation boundary 2 lives here: stream and interruption errors are
     * turned into an {@link StopReason#ABORTED} (if cancelled) or
     * {@link StopReason#ERROR} assistant message.
     */
    private Message.Assistant invokeModelSafely(LoopState state, AgentLoopConfig config,
                                                  CancellationSignal cancellation) {
        var request = new ModelRequest(
                config.model(),
                state.context().systemPrompt(),
                projectMessages(state.context().messages()),
                toolSpecs(state.context().tools())
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

    private LoopResult abortRun(LoopState state, RunEventEmitter events) {
        var aborted = abortedAssistant("cancelled");
        var wrapped = StandardAgentMessage.of(aborted);
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

    /**
     * Wave-0 default message projection (seed of Wave 3 {@code MessageProjector}):
     * unwrap {@link StandardAgentMessage} to {@link Message}; filter out unknown
     * product messages (keep user/assistant/toolResult, drop the rest).
     */
    private static List<Message> projectMessages(List<AgentMessage> messages) {
        return messages.stream()
                .filter(StandardAgentMessage.class::isInstance)
                .map(StandardAgentMessage.class::cast)
                .map(StandardAgentMessage::message)
                .toList();
    }

    private static List<ToolSpec> toolSpecs(List<AgentTool<?>> tools) {
        return tools.stream().map(AgentTool::spec).toList();
    }

}
