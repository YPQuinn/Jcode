package site.pplee.jcode.agentcore.model;

import java.util.List;
import java.util.Objects;

public record ToolResult(
        List<Content> content,
        boolean error,
        boolean terminate
) {
    public ToolResult {
        Objects.requireNonNull(content, "content must not be null");
        content = List.copyOf(content);
    }

    public static ToolResult success(List<Content> content) {
        return new ToolResult(content, false, false);
    }

    public static ToolResult success(List<Content> content, boolean terminate) {
        return new ToolResult(content, false, terminate);
    }

    public static ToolResult failure(String message) {
        Objects.requireNonNull(message, "message must not be null");
        return new ToolResult(List.of(new Content.Text(message)), true, false);
    }
}
