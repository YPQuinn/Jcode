package site.pplee.jcode.ai.message;

/**
 * Token-usage metadata reported with a model result. Part of the standard
 * model-result metadata owned by {@code ai} (provider/api/model identity,
 * usage, stop reason, error message).
 *
 * <p>Wired into {@link Message.Assistant} in a later wave once streaming and
 * per-provider usage reporting land (Wave 1). Until then it is the canonical
 * value type for usage so adapters have a single source of truth.
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
