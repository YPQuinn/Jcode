package site.pplee.jcode.agentcore.spi;

import site.pplee.jcode.agentcore.concurrent.CancellationToken;
import site.pplee.jcode.agentcore.model.ToolExecutionMode;
import site.pplee.jcode.agentcore.model.ToolResult;

import java.util.concurrent.CompletionStage;

public interface AgentTool<A> {
    String name();

    Class<A> argumentType();

    default ToolExecutionMode executionMode() {
        return ToolExecutionMode.PARALLEL;
    }

    CompletionStage<ToolResult> execute(
            String toolCallId,
            A arguments,
            CancellationToken cancellation
    );
}
