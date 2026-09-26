package site.pplee.jcode.protocol;

import java.util.Objects;

/** Idempotent request to start one prompt or continuation. */
public record RunCommand(
        String commandId,
        String runId,
        RunKind kind,
        String text,
        String expectedLeafId
) {
    public RunCommand {
        ProtocolIds.require(commandId, "commandId");
        ProtocolIds.require(runId, "runId");
        Objects.requireNonNull(kind, "kind must not be null");
        if (kind == RunKind.PROMPT && (text == null || text.isEmpty())) {
            throw new IllegalArgumentException("prompt text must not be empty");
        }
        if (kind == RunKind.CONTINUE && text != null) {
            throw new IllegalArgumentException("continue must not include prompt text");
        }
        if (expectedLeafId != null) {
            ProtocolIds.require(expectedLeafId, "expectedLeafId");
        }
    }

    @Override
    public String toString() {
        return "RunCommand[commandId=" + commandId + ", runId=" + runId
                + ", kind=" + kind + ", text=redacted, expectedLeafId=" + expectedLeafId + ']';
    }
}
