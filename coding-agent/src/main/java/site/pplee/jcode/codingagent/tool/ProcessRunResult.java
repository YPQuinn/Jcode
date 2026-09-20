package site.pplee.jcode.codingagent.tool;

import java.util.Objects;
import java.util.Optional;

/** Typed terminal state of one process execution. */
record ProcessRunResult(Termination termination, Integer exitCode, Optional<String> diagnostic) {
    ProcessRunResult {
        termination = Objects.requireNonNull(termination, "termination must not be null");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic must not be null");
        if (termination == Termination.EXITED && exitCode == null) {
            throw new IllegalArgumentException("exited process must have an exit code");
        }
        if (termination != Termination.EXITED && exitCode != null) {
            throw new IllegalArgumentException("non-exited process must not have an exit code");
        }
    }

    static ProcessRunResult exited(int exitCode) {
        return new ProcessRunResult(Termination.EXITED, exitCode, Optional.empty());
    }

    static ProcessRunResult timedOut() {
        return terminal(Termination.TIMED_OUT, null);
    }

    static ProcessRunResult cancelled() {
        return terminal(Termination.CANCELLED, null);
    }

    static ProcessRunResult startFailed() {
        return terminal(Termination.START_FAILED, "process start failed");
    }

    static ProcessRunResult resourceBusy() {
        return terminal(Termination.RESOURCE_BUSY, "process capacity is exhausted");
    }

    static ProcessRunResult failed(String diagnostic) {
        return terminal(Termination.FAILED, diagnostic);
    }

    static ProcessRunResult terminationFailed() {
        return terminal(Termination.TERMINATION_FAILED, "process did not terminate after escalation");
    }

    private static ProcessRunResult terminal(Termination termination, String diagnostic) {
        return new ProcessRunResult(
                termination, null, Optional.ofNullable(diagnostic));
    }

    @Override
    public String toString() {
        return "ProcessRunResult[termination=" + termination
                + ", exitCode=" + exitCode
                + ", diagnostic=" + (diagnostic.isPresent() ? "present" : "absent") + ']';
    }

    enum Termination {
        EXITED,
        TIMED_OUT,
        CANCELLED,
        START_FAILED,
        RESOURCE_BUSY,
        FAILED,
        TERMINATION_FAILED
    }
}
