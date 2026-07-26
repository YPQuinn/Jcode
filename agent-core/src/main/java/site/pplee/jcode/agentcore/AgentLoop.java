package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.concurrent.CancellationToken;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.model.AgentContext;
import site.pplee.jcode.agentcore.model.AgentMessage;
import site.pplee.jcode.agentcore.model.Content;
import site.pplee.jcode.agentcore.model.LoopResult;
import site.pplee.jcode.agentcore.model.StopReason;
import site.pplee.jcode.agentcore.queue.PendingMessageSource;
import site.pplee.jcode.agentcore.spi.LlmRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * Package-private agent loop. The only public run entry is {@code Agent}
 * (Step 8); this class is constructed by {@code Agent} and its methods are
 * invoked on {@code Agent}'s virtual-thread-per-task executor.
 *
 * Step 5 implements the no-tool skeleton: the full 15-step {@code runLoop}
 * sequence with {@code executeToolCalls} as a fail-fast stub. Step 6 replaces
 * the stub with real tool lookup / argument mapping / parallel execution.
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
            CancellationToken cancellation
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
            state.append(p);
            emit(new AgentEvent.MessageCompleted(p), config);
        }
        return runLoop(state, config, cancellation);
    }

    LoopResult continueRun(
            AgentContext context,
            AgentLoopConfig config,
            CancellationToken cancellation
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");

        var state = new LoopState(context);
        emit(new AgentEvent.AgentStarted(), config);
        emit(new AgentEvent.TurnStarted(), config);
        return runLoop(state, config, cancellation);
    }

    private LoopResult runLoop(LoopState state, AgentLoopConfig config, CancellationToken cancellation) {
        boolean firstTurn = true;
        var pendingMessages = drain(config.steeringMessages());

        while (true) {
            var hasMoreToolCalls = true;

            while (hasMoreToolCalls || !pendingMessages.isEmpty()) {
                if (!firstTurn) {
                    emit(new AgentEvent.TurnStarted(), config);
                }
                firstTurn = false;

                if (!pendingMessages.isEmpty()) {
                    state.appendAll(pendingMessages);
                    for (var m : pendingMessages) {
                        emit(new AgentEvent.MessageCompleted(m), config);
                    }
                    pendingMessages = List.of();
                }

                AgentMessage.Assistant assistant;
                if (cancellation.isCancelled()) {
                    // cancellation boundary 1: before model call
                    assistant = new AgentMessage.Assistant(
                            List.of(), StopReason.ABORTED, "cancelled", Instant.now());
                } else {
                    var request = new LlmRequest(
                            config.model(),
                            state.context().systemPrompt(),
                            state.context().messages(),
                            state.context().tools()
                    );
                    try {
                        assistant = config.llmClient()
                                .generate(request, cancellation, config.llmEventSink())
                                .toCompletableFuture()
                                .join();
                    } catch (CompletionException e) {
                        // cancellation boundary 2: failed future; boolean distinguishes ABORTED/ERROR
                        var reason = cancellation.isCancelled() ? StopReason.ABORTED : StopReason.ERROR;
                        var msg = cancellation.isCancelled() ? "cancelled" : causeMessage(e);
                        assistant = new AgentMessage.Assistant(List.of(), reason, msg, Instant.now());
                    }
                }

                state.append(assistant);
                emit(new AgentEvent.MessageCompleted(assistant), config);

                if (assistant.stopReason().isTerminalFailure()) {
                    emit(new AgentEvent.TurnCompleted(assistant, List.of()), config);
                    var result = state.result();
                    emit(new AgentEvent.AgentCompleted(result), config);
                    return result;
                }

                var toolCalls = extractToolCalls(assistant);
                List<AgentMessage.ToolResult> toolResults;
                if (toolCalls.isEmpty()) {
                    toolResults = List.of();
                } else {
                    toolResults = executeToolCalls(toolCalls, state, config, cancellation);
                }
                for (var tr : toolResults) {
                    state.append(tr);
                    emit(new AgentEvent.MessageCompleted(tr), config);
                }

                emit(new AgentEvent.TurnCompleted(assistant, toolResults), config);

                pendingMessages = drain(config.steeringMessages());
                hasMoreToolCalls = !toolResults.isEmpty() && !allTerminated(toolResults);
            }

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

    private List<AgentMessage.ToolResult> executeToolCalls(
            List<Content.ToolCall> toolCalls,
            LoopState state,
            AgentLoopConfig config,
            CancellationToken cancellation
    ) {
        // Step 5 stub: tool execution is implemented in Step 6.
        throw new UnsupportedOperationException("tool execution: Step 6");
    }

    private static boolean allTerminated(List<AgentMessage.ToolResult> toolResults) {
        return toolResults.stream().allMatch(AgentMessage.ToolResult::terminate);
    }

    private static List<Content.ToolCall> extractToolCalls(AgentMessage.Assistant assistant) {
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

    private static String causeMessage(CompletionException e) {
        var cause = e.getCause();
        var src = cause != null ? cause : e;
        var msg = src.getMessage();
        return msg != null ? msg : "unknown error";
    }

    private static void emit(AgentEvent event, AgentLoopConfig config) {
        config.eventSink().emit(event).toCompletableFuture().join();
    }
}
