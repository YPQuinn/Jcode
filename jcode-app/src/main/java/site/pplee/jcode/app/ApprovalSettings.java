package site.pplee.jcode.app;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Explicit tools that require one-time approval and their maximum wait. */
public record ApprovalSettings(Set<String> toolNames, Duration timeout) {
    public ApprovalSettings {
        toolNames = Set.copyOf(Objects.requireNonNull(toolNames, "toolNames must not be null"));
        if (toolNames.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("toolNames must not contain blanks");
        }
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("timeout must be positive and at most one hour");
        }
    }

    public static ApprovalSettings none() {
        return new ApprovalSettings(Set.of(), Duration.ofMinutes(5));
    }

    boolean requires(String toolName) {
        return toolNames.contains(toolName);
    }
}
