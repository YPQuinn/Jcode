package site.pplee.jcode.protocol;

import java.util.Objects;

/** Queryable approval; description is bounded and excludes known secret fields. */
public record ApprovalView(
        String sessionId,
        String approvalId,
        String runId,
        String toolCallId,
        String toolName,
        String requestDigest,
        String description,
        ApprovalStatus status
) {
    public ApprovalView {
        ProtocolIds.require(sessionId, "sessionId");
        ProtocolIds.require(approvalId, "approvalId");
        ProtocolIds.require(runId, "runId");
        ProtocolIds.require(toolCallId, "toolCallId");
        ProtocolIds.require(toolName, "toolName");
        ProtocolIds.require(requestDigest, "requestDigest");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
