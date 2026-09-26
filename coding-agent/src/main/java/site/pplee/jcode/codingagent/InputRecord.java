package site.pplee.jcode.codingagent;

import java.util.Objects;

/** Point-in-time result of a run-scoped supplemental input. */
public record InputRecord(
        InputRequest request,
        InputStatus status,
        String entryId,
        String terminalReason
) {
    public InputRecord {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if ((status == InputStatus.APPLIED_TO_CONTEXT) != (entryId != null)) {
            throw new IllegalArgumentException("entryId is required exactly when the input is applied");
        }
        if (status == InputStatus.NOT_APPLIED && terminalReason == null) {
            throw new IllegalArgumentException("not-applied inputs need a terminal reason");
        }
    }

    public String inputId() {
        return request.inputId();
    }

    public String targetRunId() {
        return request.targetRunId();
    }

    /** Avoid printing user input in diagnostics. */
    @Override
    public String toString() {
        return "InputRecord[inputId=" + inputId() + ", targetRunId=" + targetRunId()
                + ", status=" + status + ", entryId=" + entryId
                + ", terminalReason=" + terminalReason + ']';
    }
}
