package site.pplee.jcode.protocol;

import java.util.Objects;

/** Decision bound to the exact approval and prepared tool request shown to a client. */
public record ApprovalCommand(
        String approvalId,
        String toolCallId,
        String requestDigest,
        ApprovalDecision decision
) {
    public ApprovalCommand {
        ProtocolIds.require(approvalId, "approvalId");
        ProtocolIds.require(toolCallId, "toolCallId");
        ProtocolIds.require(requestDigest, "requestDigest");
        Objects.requireNonNull(decision, "decision must not be null");
    }
}
