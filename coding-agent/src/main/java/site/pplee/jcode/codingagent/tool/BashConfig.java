package site.pplee.jcode.codingagent.tool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit Bash configuration. A null environment inherits the host environment;
 * a non-null map replaces it. With both timeout values null, execution is unbounded.
 * An omitted call timeout uses the default, then the optional maximum.
 */
public record BashConfig(
        Path executable,
        Map<String, String> environment,
        Duration defaultTimeout,
        Duration maximumTimeout
) {
    public BashConfig {
        executable = absolutePath(executable, "executable");
        environment = environment == null ? null : environmentSnapshot(environment);
        defaultTimeout = positiveDuration(defaultTimeout, "defaultTimeout");
        maximumTimeout = positiveDuration(maximumTimeout, "maximumTimeout");
        if (defaultTimeout != null && maximumTimeout != null && defaultTimeout.compareTo(maximumTimeout) > 0) {
            throw new IllegalArgumentException("defaultTimeout must not exceed maximumTimeout");
        }
    }

    /** Use the normal shell environment with no default timeout. */
    public BashConfig(Path executable) {
        this(executable, null, null, null);
    }

    /** Use a chosen environment with no default timeout. */
    public BashConfig(Path executable, Map<String, String> environment) {
        this(executable, environment, null, null);
    }

    @Override
    public String toString() {
        return "BashConfig[executable=redacted"
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

    private static Duration positiveDuration(Duration duration, String name) {
        if (duration == null) {
            return null;
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        if (duration.getNano() != 0) {
            throw new IllegalArgumentException(name + " must use whole seconds");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(name + " exceeds the scheduler range", failure);
        }
        return duration;
    }
}
