package site.pplee.jcode.codingagent.tool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable process request; null environment inherits, and null timeout has no deadline. */
record ProcessRequest(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        Duration timeout,
        OutputMode outputMode
) {
    ProcessRequest {
        command = List.copyOf(Objects.requireNonNull(command, "command must not be null"));
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (command.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("command must not contain null");
        }
        // Keep symlink-sensitive parent traversal for tool-supplied search roots.
        workingDirectory = Objects.requireNonNull(
                workingDirectory, "workingDirectory must not be null")
                .toAbsolutePath();
        if (environment != null) {
            environment = BashConfig.environmentSnapshot(environment);
        }
        if (timeout != null) {
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            try {
                timeout.toNanos();
            } catch (ArithmeticException failure) {
                throw new IllegalArgumentException("timeout exceeds the scheduler range", failure);
            }
        }
        outputMode = Objects.requireNonNull(outputMode, "outputMode must not be null");
    }

    @Override
    public String toString() {
        return "ProcessRequest[command=redacted, workingDirectory=redacted, "
                + "environment=redacted, timeout=" + timeout
                + ", outputMode=" + outputMode + ']';
    }

    enum OutputMode {
        MERGED,
        SEPARATE
    }
}
