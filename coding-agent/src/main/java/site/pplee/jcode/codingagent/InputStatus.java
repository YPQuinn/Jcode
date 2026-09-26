package site.pplee.jcode.codingagent;

/** Observable outcome of one run-scoped supplemental input. */
public enum InputStatus {
    PENDING,
    APPLIED_TO_CONTEXT,
    NOT_APPLIED,
    RECONCILIATION_REQUIRED
}
