package site.pplee.jcode.codingagent.tool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable request for one local process execution. */
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
        Objects.requireNonNull(environment, "environment must not be null");
        var environmentCopy = new LinkedHashMap<String, String>();
        environment.forEach((name, value) -> environmentCopy.put(
                Objects.requireNonNull(name, "environment name must not be null"),
                Objects.requireNonNull(value, "environment value must not be null")));
        environment = Collections.unmodifiableMap(environmentCopy);
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
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
