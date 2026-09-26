package site.pplee.jcode.protocol;

import java.util.Objects;

/** Queryable run receipt and terminal summary; full content belongs to Session history. */
public record RunView(
        String sessionId,
        String commandId,
        String runId,
        RunStatus status,
        boolean cancelRequested,
        String stopReason,
        String text,
        boolean textTruncated,
        String errorMessage
) {
    public RunView {
        ProtocolIds.require(sessionId, "sessionId");
        ProtocolIds.require(commandId, "commandId");
        ProtocolIds.require(runId, "runId");
        Objects.requireNonNull(status, "status must not be null");
        if (!status.terminal() && (stopReason != null || text != null
                || textTruncated || errorMessage != null)) {
            throw new IllegalArgumentException("nonterminal run cannot have a result");
        }
    }
}
