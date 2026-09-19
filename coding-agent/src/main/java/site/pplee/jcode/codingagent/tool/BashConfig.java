package site.pplee.jcode.codingagent.tool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit process configuration reserved for the built-in bash tool. */
public record BashConfig(
        Path executable,
        Map<String, String> environment,
        Duration defaultTimeout,
        Duration maximumTimeout
) {
    private static final Duration HARD_TIMEOUT_LIMIT = Duration.ofSeconds(3_600);
    private static final Set<String> IMPLICIT_INITIALIZATION_VARIABLES =
            Set.of("BASH_ENV", "ENV", "SHELLOPTS", "BASHOPTS");

    public BashConfig {
        executable = absolutePath(executable, "executable");
        environment = environmentSnapshot(environment);
        rejectImplicitInitialization(environment);
        defaultTimeout = positiveDuration(defaultTimeout, "defaultTimeout");
        maximumTimeout = positiveDuration(maximumTimeout, "maximumTimeout");
        if (maximumTimeout.compareTo(HARD_TIMEOUT_LIMIT) > 0) {
            throw new IllegalArgumentException("maximumTimeout must not exceed 3600 seconds");
        }
        if (defaultTimeout.compareTo(maximumTimeout) > 0) {
            throw new IllegalArgumentException("defaultTimeout must not exceed maximumTimeout");
        }
    }

    @Override
    public String toString() {
        return "BashConfig[executable=" + executable
                + ", environment=redacted"
                + ", defaultTimeout=" + defaultTimeout
                + ", maximumTimeout=" + maximumTimeout + ']';
    }

    static Path absolutePath(Path path, String name) {
        Objects.requireNonNull(path, name + " must not be null");
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(name + " must be absolute");
        }
        return path.normalize();
    }

    static Map<String, String> environmentSnapshot(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        var snapshot = new LinkedHashMap<String, String>();
        environment.forEach((name, value) -> snapshot.put(
                Objects.requireNonNull(name, "environment name must not be null"),
                Objects.requireNonNull(value, "environment value must not be null")));
        return Collections.unmodifiableMap(snapshot);
    }

    private static void rejectImplicitInitialization(Map<String, String> environment) {
        for (String name : IMPLICIT_INITIALIZATION_VARIABLES) {
            if (environment.containsKey(name)) {
                throw new IllegalArgumentException(
                        "environment must not contain shell initialization variable: " + name);
            }
        }
    }

    private static Duration positiveDuration(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
