package site.pplee.jcode.protocol;

import java.util.Objects;

/** Queryable input receipt with its authoritative history association. */
public record InputView(
        String sessionId,
        String commandId,
        String inputId,
        String targetRunId,
        InputMode mode,
        InputStatus status,
        String entryId,
        String terminalReason
) {
    public InputView {
        ProtocolIds.require(sessionId, "sessionId");
        ProtocolIds.require(inputId, "inputId");
        ProtocolIds.require(targetRunId, "targetRunId");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if ((status == InputStatus.APPLIED_TO_CONTEXT) != (entryId != null)) {
            throw new IllegalArgumentException("entryId must identify exactly an applied input");
        }
    }
}
