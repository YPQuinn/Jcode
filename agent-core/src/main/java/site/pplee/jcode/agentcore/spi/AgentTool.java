package site.pplee.jcode.agentcore.spi;

import site.pplee.jcode.agentcore.concurrent.CancellationToken;
import site.pplee.jcode.agentcore.model.ToolExecutionMode;
import site.pplee.jcode.agentcore.model.ToolResult;

import java.util.concurrent.CompletionStage;

/**
 * SPI for a callable tool. The core layer converts the model's raw
 * {@link com.fasterxml.jackson.databind.JsonNode} arguments to {@code A}
 * via the shared {@link com.fasterxml.jackson.databind.ObjectMapper}; any
 * conversion or execution failure becomes an error tool result and is never
 * thrown into the loop.
 */
public interface AgentTool<A> {
    /** Stable tool name the model uses to request it. */
    String name();

    /** Strong type the raw arguments are converted into. */
    Class<A> argumentType();

    /** Default {@link ToolExecutionMode#PARALLEL}; override for sequential. */
    default ToolExecutionMode executionMode() {
        return ToolExecutionMode.PARALLEL;
    }

    /** Run the tool; report success or failure via the returned {@link ToolResult}. */
    CompletionStage<ToolResult> execute(
            String toolCallId,
            A arguments,
            CancellationToken cancellation
    );
}
