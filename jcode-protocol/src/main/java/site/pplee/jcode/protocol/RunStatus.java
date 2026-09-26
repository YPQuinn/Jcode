package site.pplee.jcode.protocol;

/** Application-visible lifecycle of one product run. */
public enum RunStatus {
    ACCEPTED,
    RUNNING,
    CANCELLING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
