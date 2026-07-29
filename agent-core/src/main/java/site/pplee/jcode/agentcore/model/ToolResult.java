package site.pplee.jcode.agentcore.model;

import java.util.List;
import java.util.Objects;

/**
 * Internal tool execution outcome. {@code error} marks a failure the model
 * can react to; {@code terminate} requests stopping the tool chain.
 */
public record ToolResult(
        List<Content> content,
        boolean error,
        boolean terminate
) {
    public ToolResult {
        Objects.requireNonNull(content, "content must not be null");
        content = List.copyOf(content);
    }

    /** Successful result that does not terminate the chain. */
    public static ToolResult success(List<Content> content) {
        return new ToolResult(content, false, false);
    }

    /** Successful result with an explicit terminate flag. */
    public static ToolResult success(List<Content> content, boolean terminate) {
        return new ToolResult(content, false, terminate);
    }

    /** Failed result carrying an error message; does not terminate the chain. */
    public static ToolResult failure(String message) {
        Objects.requireNonNull(message, "message must not be null");
        return new ToolResult(List.of(new Content.Text(message)), true, false);
    }
}
