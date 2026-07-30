package site.pplee.jcode.ai.message;

/**
 * Token-usage metadata reported with a model result. Part of the standard
 * model-result metadata owned by {@code ai} (provider/api/model identity,
 * usage, stop reason, error message).
 *
 * <p>Wired into {@link Message.Assistant#usage()} as the canonical value type
 * for token-usage metadata. Adapters provide real usage in the final
 * {@code Done}/{@code Error} event; partial messages use {@link #zero()}.
 */
public record Usage(
        long input,
        long output,
        long cacheRead,
        long cacheWrite,
        long totalTokens
) {
    public Usage {
        if (totalTokens < 0) {
            throw new IllegalArgumentException("totalTokens must not be negative");
        }
        if (input < 0 || output < 0 || cacheRead < 0 || cacheWrite < 0) {
            throw new IllegalArgumentException("usage fields must not be negative");
        }
    }

    /** A zeroed usage for tests and "no usage reported" sentinels. */
    public static Usage zero() {
        return new Usage(0, 0, 0, 0, 0);
    }
}
