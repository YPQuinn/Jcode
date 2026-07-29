package site.pplee.jcode.agentcore.model;

/**
 * Whether a batch of tool calls runs in parallel or sequentially. Any tool
 * declaring {@code SEQUENTIAL} forces the whole batch sequential.
 */
public enum ToolExecutionMode {
    SEQUENTIAL,
    PARALLEL
}
