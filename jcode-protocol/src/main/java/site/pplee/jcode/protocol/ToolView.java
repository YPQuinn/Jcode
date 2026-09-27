package site.pplee.jcode.protocol;

import java.util.Objects;

/** Latest bounded output snapshot for one tool call. */
public record ToolView(
        String toolCallId,
        String runId,
        String toolName,
        ToolStatus status,
        String outputTail,
        boolean truncated,
        boolean error
) {
    public ToolView {
        ProtocolIds.require(toolCallId, "toolCallId");
        ProtocolIds.require(runId, "runId");
        ProtocolIds.require(toolName, "toolName");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(outputTail, "outputTail must not be null");
    }
}
