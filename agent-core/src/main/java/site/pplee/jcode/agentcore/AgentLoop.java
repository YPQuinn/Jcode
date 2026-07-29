package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.queue.PendingMessageSource;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;

import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.tool.ToolSpec;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * Package-private agent loop. The only public run entry is {@code Agent}
 * (Step 8); this class is constructed by {@code Agent} and its methods are
 * invoked on {@code Agent}'s virtual-thread-per-task executor.
 *
 * Implements the full 15-step {@code runLoop} sequence: model call, tool-call
 * extraction, sequential/parallel tool execution with ordered result
 * write-back, LENGTH truncation protection, steering/follow-up queues, and
 * cancellation boundaries (before turn, before tool batch, failed future).
 *
 * <p>Model-call seam (Wave 0): before each call the loop projects the open
 * {@link AgentMessage} transcript to {@link Message standard ai messages}
 * (inline default projection — the seed of Wave 3's {@code MessageProjector})
 * and extracts {@link ToolSpec declarable specs} from {@link AgentTool}s, then
 * invokes the {@link ModelClient}. The returned {@link Message.Assistant} is
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
                    state.appendAll(pendingMessages);
                    for (var m : pendingMessages) {
                        emit(new AgentEvent.MessageCompleted(m), config);
                    }
                    pendingMessages = List.of();
                }

                // step 5: call model (cancellation boundary 2 handles failed future)
                var request = new ModelRequest(
                        config.model(),
                        state.context().systemPrompt(),
                        projectMessages(state.context().messages()),
                        toolSpecs(state.context().tools())
                );
                Message.Assistant assistantMessage;
                try {
                    assistantMessage = config.modelClient()
                            .generate(request, cancellation)
                            .toCompletableFuture()
                            .join();
                } catch (CompletionException e) {
                    var reason = cancellation.isCancelled() ? StopReason.ABORTED : StopReason.ERROR;
                    var msg = cancellation.isCancelled() ? "cancelled" : causeMessage(e);
                    assistantMessage = new Message.Assistant(List.of(), reason, msg, Instant.now());
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
                List<Message.ToolResultMessage> toolResultMessages;
                if (toolCalls.isEmpty()) {
                    toolResultMessages = List.of();
                } else if (assistantMessage.stopReason() == StopReason.LENGTH) {
                    toolResultMessages = failTruncatedToolCalls(toolCalls, config);
                } else {
                    toolResultMessages = executeToolCalls(toolCalls, state, config, cancellation);
                }
                for (var trm : toolResultMessages) {
                    var wrapped = StandardAgentMessage.of(trm);
                    state.append(wrapped);
                    emit(new AgentEvent.MessageCompleted(wrapped), config);
                }

                // step 11: TurnCompleted
                emit(new AgentEvent.TurnCompleted(assistantMessage, toolResultMessages), config);

                // step 12: drain steering; decide inner loop continuation
                pendingMessages = drain(config.steeringMessages());
                hasMoreToolCalls = !toolResultMessages.isEmpty() && !allTerminated(toolResultMessages);
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

    private List<Message.ToolResultMessage> executeToolCalls(
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
                ? executeSequential(toolCalls, toolMap, config, cancellation)
                : executeParallel(toolCalls, toolMap, config, cancellation);
    }

    private List<Message.ToolResultMessage> executeSequential(
            List<Content.ToolCall> toolCalls,
            Map<String, AgentTool<?>> toolMap,
            AgentLoopConfig config,
            CancellationSignal cancellation
    ) {
        var messages = new ArrayList<Message.ToolResultMessage>();
        for (var tc : toolCalls) {
            if (cancellation.isCancelled()) {
                break;
            }
            emit(new AgentEvent.ToolStarted(tc), config);
            var internal = executeOneToolCallSync(tc, toolMap, config.objectMapper(), cancellation);
            var msg = toToolResultMessage(tc, internal);
            emit(new AgentEvent.ToolCompleted(msg), config);
            messages.add(msg);
        }
        return List.copyOf(messages);
    }

    private List<Message.ToolResultMessage> executeParallel(
            List<Content.ToolCall> toolCalls,
            Map<String, AgentTool<?>> toolMap,
            AgentLoopConfig config,
            CancellationSignal cancellation
    ) {
        var futures = new ArrayList<CompletableFuture<ToolExecutionResult>>();
        var submitted = new ArrayList<Content.ToolCall>();
        for (var tc : toolCalls) {
            if (cancellation.isCancelled()) {
                break;
            }
            emit(new AgentEvent.ToolStarted(tc), config);
            submitted.add(tc);
            futures.add(CompletableFuture.supplyAsync(
                    () -> executeOneToolCallSync(tc, toolMap, config.objectMapper(), cancellation),
                    executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        var messages = new ArrayList<Message.ToolResultMessage>();
        for (int i = 0; i < futures.size(); i++) {
            var internal = futures.get(i).join();
            var msg = toToolResultMessage(submitted.get(i), internal);
            emit(new AgentEvent.ToolCompleted(msg), config);
            messages.add(msg);
        }
        return List.copyOf(messages);
    }

    private ToolExecutionResult executeOneToolCallSync(
            Content.ToolCall toolCall,
            Map<String, AgentTool<?>> toolMap,
            ObjectMapper objectMapper,
            CancellationSignal cancellation
    ) {
        var tool = toolMap.get(toolCall.name());
        if (tool == null) {
            return ToolExecutionResult.failure("tool not found: " + toolCall.name());
        }
        return executeTyped(tool, toolCall, objectMapper, cancellation);
    }

    private <A> ToolExecutionResult executeTyped(
            AgentTool<A> tool,
            Content.ToolCall toolCall,
            ObjectMapper objectMapper,
            CancellationSignal cancellation
    ) {
        A args;
        try {
            args = objectMapper.treeToValue(toolCall.arguments(), tool.argumentType());
        } catch (Exception e) {
            return ToolExecutionResult.failure("argument conversion failed: " + e.getMessage());
        }
        try {
            return tool.execute(toolCall.id(), args, cancellation)
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException e) {
            return ToolExecutionResult.failure("tool execution failed: " + causeMessage(e));
        } catch (RuntimeException e) {
            return ToolExecutionResult.failure("tool execution failed: " + e.getMessage());
        }
    }

    private List<Message.ToolResultMessage> failTruncatedToolCalls(
            List<Content.ToolCall> toolCalls,
            AgentLoopConfig config
    ) {
        var messages = new ArrayList<Message.ToolResultMessage>();
        for (var tc : toolCalls) {
            emit(new AgentEvent.ToolStarted(tc), config);
            var msg = new Message.ToolResultMessage(
                    tc.id(),
                    tc.name(),
                    List.of(new Content.Text(
                            "tool call \"" + tc.name() + "\" not executed: "
                                    + "response hit output token limit, arguments may be truncated")),
                    true,
                    false,
                    Instant.now()
            );
            emit(new AgentEvent.ToolCompleted(msg), config);
            messages.add(msg);
        }
        return List.copyOf(messages);
    }

    private LoopResult abortRun(LoopState state, AgentLoopConfig config) {
        var aborted = new Message.Assistant(
                List.of(), StopReason.ABORTED, "cancelled", Instant.now());
        var wrapped = StandardAgentMessage.of(aborted);
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
                internal.terminate(),
                Instant.now()
        );
    }

    private static boolean allTerminated(List<Message.ToolResultMessage> toolResults) {
        return toolResults.stream().allMatch(Message.ToolResultMessage::terminate);
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

    private static String causeMessage(CompletionException e) {
        var cause = e.getCause();
        var src = cause != null ? cause : e;
        var msg = src.getMessage();
        return msg != null ? msg : "unknown error";
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
}
