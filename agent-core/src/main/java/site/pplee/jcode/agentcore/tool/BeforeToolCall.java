package site.pplee.jcode.agentcore.tool;

import site.pplee.jcode.agentcore.AgentContext;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Hook invoked during the prepare phase after schema validation but before
 * execution. Can allow execution to proceed or block it with a reason,
 * producing an error {@link ToolExecutionResult} instead.
 *
 * <p>The hook receives the raw validated arguments as a {@link JsonNode} (not
 * the converted strong type) so it stays generic across all tools. The stage
 * completes when the hook is done; a slow hook blocks the run.
 */
@FunctionalInterface
public interface BeforeToolCall {
    /**
     * @param call             the model-requested tool call
     * @param tool             the resolved tool (never null)
     * @param preparedArguments the arguments after {@code prepareArguments} and schema validation
     * @param context          the current agent context
     * @param cancellation     the run's cancellation signal
     */
    CompletionStage<Decision> beforeToolCall(
            Content.ToolCall call,
            AgentTool<?> tool,
            JsonNode preparedArguments,
            AgentContext context,
            CancellationSignal cancellation
    );

    /** Default hook that always allows execution. */
    static BeforeToolCall noop() {
        return (call, tool, args, ctx, c) ->
                CompletableFuture.completedStage(new Decision.Proceed());
    }

    /** Decision returned by the before hook. */
    sealed interface Decision permits Decision.Proceed, Decision.Block {
        /** Allow the tool to execute. */
        record Proceed() implements Decision {}

        /** Block execution; the tool is not run and an error result is produced. */
        record Block(String reason) implements Decision {
            public Block {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }
    }
}
