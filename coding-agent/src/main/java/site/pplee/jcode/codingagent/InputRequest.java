package site.pplee.jcode.codingagent;

import java.util.Objects;

/** Immutable request to inject one supplemental input into a specified product run. */
public record InputRequest(String inputId, String targetRunId, InputMode mode, String text) {
    public InputRequest {
        requireText(inputId, "inputId");
        requireText(targetRunId, "targetRunId");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(text, "text must not be null");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /** Avoid printing user input in diagnostics. */
    @Override
    public String toString() {
        return "InputRequest[inputId=" + inputId + ", targetRunId=" + targetRunId
                + ", mode=" + mode + ", text=redacted]";
    }
}
