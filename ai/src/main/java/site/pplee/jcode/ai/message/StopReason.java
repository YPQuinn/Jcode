package site.pplee.jcode.ai.message;

/**
 * Why the model stopped generating. Terminal failures ({@link #ERROR},
 * {@link #ABORTED}) end the run immediately.
 *
 * <p>Standard model-stream termination protocol.
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
