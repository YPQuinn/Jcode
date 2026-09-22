package site.pplee.jcode.codingagent.settings;

/** Effective immutable compaction settings for one session. */
public record CompactionSettings(
        boolean enabled,
        int reserveTokens,
        int keepRecentTokens
) {
    public static final int DEFAULT_RESERVE_TOKENS = 16_384;
    public static final int DEFAULT_KEEP_RECENT_TOKENS = 20_000;

    public CompactionSettings {
        if (reserveTokens <= 0) {
            throw new IllegalArgumentException("reserveTokens must be positive");
        }
        if (keepRecentTokens <= 0) {
            throw new IllegalArgumentException("keepRecentTokens must be positive");
        }
    }

    public static CompactionSettings disabled() {
        return new CompactionSettings(false, DEFAULT_RESERVE_TOKENS, DEFAULT_KEEP_RECENT_TOKENS);
    }
}
