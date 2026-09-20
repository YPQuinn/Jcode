package site.pplee.jcode.codingagent.tool;

import java.util.Objects;

/** Arguments accepted by the local {@code bash} tool. */
public record BashToolArguments(String command, Long timeout) {
    public BashToolArguments {
        Objects.requireNonNull(command, "command must not be null");
    }

    /** Avoid placing shell source in incidental diagnostics. */
    @Override
    public String toString() {
        return "BashToolArguments[command=redacted, timeout=" + timeout + ']';
    }
}
