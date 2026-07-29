package site.pplee.jcode.agentcore.tool;

/**
 * Whether a batch of tool calls runs in parallel or sequentially. Any tool
 * declaring {@code SEQUENTIAL} forces the whole batch sequential.
 *
 * <p>Agent-runtime scheduling semantics; lives in {@code agent-core}, not
 * {@code ai} (the declarable {@link site.pplee.jcode.ai.tool.ToolSpec} carries
 * no execution semantics).
 */
public enum ToolExecutionMode {
    SEQUENTIAL,
    PARALLEL
}
