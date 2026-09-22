package site.pplee.jcode.codingagent.settings;

import java.util.Objects;
import java.util.Optional;

/** Sparse compaction object used by one settings precedence layer. */
public record CompactionSettingsLayer(
        Optional<Boolean> enabled,
        Optional<Integer> reserveTokens,
        Optional<Integer> keepRecentTokens
) {
    public CompactionSettingsLayer {
        Objects.requireNonNull(enabled, "enabled must not be null");
        Objects.requireNonNull(reserveTokens, "reserveTokens must not be null");
        Objects.requireNonNull(keepRecentTokens, "keepRecentTokens must not be null");
        reserveTokens.ifPresent(value -> requirePositive(value, "reserveTokens"));
        keepRecentTokens.ifPresent(value -> requirePositive(value, "keepRecentTokens"));
    }

    public static CompactionSettingsLayer empty() {
        return new CompactionSettingsLayer(Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
