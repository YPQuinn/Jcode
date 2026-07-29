package site.pplee.jcode.agentcore.model;

/**
 * Why the model stopped generating. Terminal failures ({@link #ERROR},
 * {@link #ABORTED}) end the run immediately.
 */
public enum StopReason {
    STOP,
    TOOL_CALL,
    LENGTH,
    ERROR,
    ABORTED;

    /** True for {@link #ERROR} and {@link #ABORTED}. */
    public boolean isTerminalFailure() {
        return this == ERROR || this == ABORTED;
    }
}