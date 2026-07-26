package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.concurrent.CancellationToken;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.model.AgentContext;
import site.pplee.jcode.agentcore.model.AgentMessage;
import site.pplee.jcode.agentcore.model.Content;
import site.pplee.jcode.agentcore.model.LoopResult;
import site.pplee.jcode.agentcore.model.StopReason;
import site.pplee.jcode.agentcore.model.ToolExecutionMode;
import site.pplee.jcode.agentcore.model.ToolResult;
import site.pplee.jcode.agentcore.queue.PendingMessageSource;
import site.pplee.jcode.agentcore.spi.AgentTool;
import site.pplee.jcode.agentcore.spi.LlmRequest;

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
                var request = new LlmRequest(
                        config.model(),
                        state.context().systemPrompt(),
                        state.context().messages(),
                        state.context().tools()
                );
                AgentMessage.Assistant assistant;
                try {
                    assistant = config.llmClient()
                            .generate(request, cancellation, config.llmEventSink())
                            .toCompletableFuture()
                            .join();
                } catch (CompletionException e) {
                    var reason = cancellation.isCancelled() ? StopReason.ABORTED : StopReason.ERROR;
                    var msg = cancellation.isCancelled() ? "cancelled" : causeMessage(e);
                    assistant = new AgentMessage.Assistant(List.of(), reason, msg, Instant.now());
                }

                // step 6: write assistant
                state.append(assistant);
                emit(new AgentEvent.MessageCompleted(assistant), config);

                // step 7: terminal failure -> end
                if (assistant.stopReason().isTerminalFailure()) {
                    emit(new AgentEvent.TurnCompleted(assistant, List.of()), config);
                    var result = state.result();
                    emit(new AgentEvent.AgentCompleted(result), config);
                    return result;
                }

                // step 8: extract tool calls
                var toolCalls = extractToolCalls(assistant);

                // cancellation boundary 3: before tool batch
                if (!toolCalls.isEmpty() && cancellation.isCancelled()) {
                    return abortRun(state, config);
                }

                // step 9-10: execute tools (LENGTH -> fail all; otherwise dispatch)
                List<AgentMessage.ToolResult> toolResults;
                if (toolCalls.isEmpty()) {
                    toolResults = List.of();
                } else if (assistant.stopReason() == StopReason.LENGTH) {
                    toolResults = failTruncatedToolCalls(toolCalls, config);
                } else {
                    toolResults = executeToolCalls(toolCalls, state, config, cancellation);
                }
                for (var tr : toolResults) {
                    state.append(tr);
                    emit(new AgentEvent.MessageCompleted(tr), config);
                }

                // step 11: TurnCompleted
                emit(new AgentEvent.TurnCompleted(assistant, toolResults), config);

                // step 12: drain steering; decide inner loop continuation
                pendingMessages = drain(config.steeringMessages());
                hasMoreToolCalls = !toolResults.isEmpty() && !allTerminated(toolResults);
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

    private List<AgentMessage.ToolResult> executeToolCalls(
            List<Content.ToolCall> toolCalls,
            LoopState state,
            AgentLoopConfig config,
            CancellationToken cancellation
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

    private List<AgentMessage.ToolResult> executeSequential(
            List<Content.ToolCall> toolCalls,
            Map<String, AgentTool<?>> toolMap,
            AgentLoopConfig config,
            CancellationToken cancellation
    ) {
        var messages = new ArrayList<AgentMessage.ToolResult>();
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

    private List<AgentMessage.ToolResult> executeParallel(
            List<Content.ToolCall> toolCalls,
            Map<String, AgentTool<?>> toolMap,
            AgentLoopConfig config,
            CancellationToken cancellation
    ) {
        var futures = new ArrayList<CompletableFuture<ToolResult>>();
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
        var messages = new ArrayList<AgentMessage.ToolResult>();
        for (int i = 0; i < futures.size(); i++) {
            var internal = futures.get(i).join();
            var msg = toToolResultMessage(submitted.get(i), internal);
            emit(new AgentEvent.ToolCompleted(msg), config);
            messages.add(msg);
        }
        return List.copyOf(messages);
    }

    private ToolResult executeOneToolCallSync(
            Content.ToolCall toolCall,
            Map<String, AgentTool<?>> toolMap,
            ObjectMapper objectMapper,
            CancellationToken cancellation
    ) {
        var tool = toolMap.get(toolCall.name());
        if (tool == null) {
            return ToolResult.failure("tool not found: " + toolCall.name());
        }
        return executeTyped(tool, toolCall, objectMapper, cancellation);
    }

    private <A> ToolResult executeTyped(
            AgentTool<A> tool,
            Content.ToolCall toolCall,
            ObjectMapper objectMapper,
            CancellationToken cancellation
    ) {
        A args;
        try {
            args = objectMapper.treeToValue(toolCall.arguments(), tool.argumentType());
        } catch (Exception e) {
            return ToolResult.failure("argument conversion failed: " + e.getMessage());
        }
        try {
            return tool.execute(toolCall.id(), args, cancellation)
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException e) {
            return ToolResult.failure("tool execution failed: " + causeMessage(e));
        } catch (RuntimeException e) {
            return ToolResult.failure("tool execution failed: " + e.getMessage());
        }
    }

    private List<AgentMessage.ToolResult> failTruncatedToolCalls(
            List<Content.ToolCall> toolCalls,
            AgentLoopConfig config
    ) {
        var messages = new ArrayList<AgentMessage.ToolResult>();
        for (var tc : toolCalls) {
            emit(new AgentEvent.ToolStarted(tc), config);
            var msg = new AgentMessage.ToolResult(
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
        var aborted = new AgentMessage.Assistant(
                List.of(), StopReason.ABORTED, "cancelled", Instant.now());
        state.append(aborted);
        emit(new AgentEvent.MessageCompleted(aborted), config);
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

    private static AgentMessage.ToolResult toToolResultMessage(
            Content.ToolCall toolCall, ToolResult internal
    ) {
        return new AgentMessage.ToolResult(
                toolCall.id(),
                toolCall.name(),
                internal.content(),
                internal.error(),
                internal.terminate(),
                Instant.now()
        );
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
