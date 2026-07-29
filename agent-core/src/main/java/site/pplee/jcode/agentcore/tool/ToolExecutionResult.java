package site.pplee.jcode.agentcore.tool;

import site.pplee.jcode.ai.message.Content;

import java.util.List;
import java.util.Objects;

/**
 * Internal tool-execution outcome produced by an {@link AgentTool}. {@code error}
 * marks a failure the model can react to; {@code terminate} requests stopping
 * the tool chain. Distinct from {@link site.pplee.jcode.ai.message.Message.ToolResultMessage},
 * the standard transcript message written back to the model — the loop maps an
 * execution result to a transcript message.
 *
 * <p>{@code terminate} stays here on the runtime result and does not enter the
 * standard LLM transcript (Wave 2 enforces this once the three-stage pipeline
 * lands; for now behavioral continuity keeps it on the transcript message too).
 */
public record ToolExecutionResult(
        List<Content> content,
        boolean error,
        boolean terminate
) {
    public ToolExecutionResult {
        Objects.requireNonNull(content, "content must not be null");
        content = List.copyOf(content);
    }

    /** Successful result that does not terminate the chain. */
    public static ToolExecutionResult success(List<Content> content) {
        return new ToolExecutionResult(content, false, false);
    }

    /** Successful result with an explicit terminate flag. */
    public static ToolExecutionResult success(List<Content> content, boolean terminate) {
        return new ToolExecutionResult(content, false, terminate);
    }

    /** Failed result carrying an error message; does not terminate the chain. */
    public static ToolExecutionResult failure(String message) {
        Objects.requireNonNull(message, "message must not be null");
        return new ToolExecutionResult(List.of(new Content.Text(message)), true, false);
    }
}
