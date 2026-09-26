package site.pplee.jcode.protocol;

import java.util.Objects;

/** Idempotent request for one identified input within a product run. */
public record InputCommand(
        String commandId,
        String inputId,
        String targetRunId,
        InputMode mode,
        String text
) {
    public InputCommand {
        ProtocolIds.require(commandId, "commandId");
        ProtocolIds.require(inputId, "inputId");
        ProtocolIds.require(targetRunId, "targetRunId");
        Objects.requireNonNull(mode, "mode must not be null");
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("input text must not be empty");
        }
    }

    @Override
    public String toString() {
        return "InputCommand[commandId=" + commandId + ", inputId=" + inputId
                + ", targetRunId=" + targetRunId + ", mode=" + mode + ", text=redacted]";
    }
}
