package site.pplee.jcode.agentcore.tool;

import site.pplee.jcode.agentcore.AgentContext;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Hook invoked during the finalize phase after execution settles. Receives
 * the {@link ToolExecutionResult} and can return a new one with any fields
 * patched (content, error, terminate).
 *
 * <p>The returned result replaces the original for all downstream purposes:
 * transcript message generation, termination check, and event emission. The
 * stage completes when the hook is done; a slow hook blocks the run.
 */
@FunctionalInterface
public interface AfterToolCall {
    /**
     * @param call    the model-requested tool call
     * @param tool    the resolved tool (never null)
     * @param result  the execution result (may be patched and returned)
     * @param context the current agent context
     * @param cancellation the run's cancellation signal
     */
    CompletionStage<ToolExecutionResult> afterToolCall(
            Content.ToolCall call,
            AgentTool<?> tool,
            ToolExecutionResult result,
            AgentContext context,
            CancellationSignal cancellation
    );

    /** Default hook that returns the result unchanged. */
    static AfterToolCall noop() {
        return (call, tool, result, ctx, c) ->
                CompletableFuture.completedStage(result);
    }
}
