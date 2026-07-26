package site.pplee.jcode.agentcore.model;

public enum StopReason {
    STOP,
    TOOL_CALL,
    LENGTH,
    ERROR,
    ABORTED;

    public boolean isTerminalFailure() {
        return this == ERROR || this == ABORTED;
    }
}