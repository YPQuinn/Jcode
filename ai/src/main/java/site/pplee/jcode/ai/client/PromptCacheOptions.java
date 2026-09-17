package site.pplee.jcode.ai.client;

import java.util.Objects;

/**
 * Provider-neutral prompt-cache and session-affinity hints for one model
 * call. These values never enter the transcript or the assistant message;
 * adapters apply them only at the request boundary.
 *
 * <p>{@link CacheRetention#PROVIDER_DEFAULT} does not override retention
 * policy; an explicit {@code cacheKey} is still a request hint.
 * {@link CacheRetention#NONE} suppresses both the cache key and
 * session-affinity headers. Blank cache key / session id are treated as
 * absent.
 *
 * <p>{@link #toString()} reports only retention and whether the two
 * optional values are present. The raw values never appear in diagnostics.
 */
public record PromptCacheOptions(
        CacheRetention retention,
        String cacheKey,
        String sessionAffinityId
) {
    public PromptCacheOptions {
        Objects.requireNonNull(retention, "retention must not be null");
        cacheKey = blankToNull(cacheKey);
        sessionAffinityId = blankToNull(sessionAffinityId);
    }

    /** Default cache policy: do not override provider retention. */
    public static PromptCacheOptions defaults() {
        return new PromptCacheOptions(CacheRetention.PROVIDER_DEFAULT, null, null);
    }

    /** Explicitly omit cache keys and session-affinity headers. */
    public static PromptCacheOptions none() {
        return new PromptCacheOptions(CacheRetention.NONE, null, null);
    }

    public PromptCacheOptions withRetention(CacheRetention retention) {
        return new PromptCacheOptions(retention, cacheKey, sessionAffinityId);
    }

    public PromptCacheOptions withCacheKey(String cacheKey) {
        return new PromptCacheOptions(retention, cacheKey, sessionAffinityId);
    }

    public PromptCacheOptions withSessionAffinityId(String sessionAffinityId) {
        return new PromptCacheOptions(retention, cacheKey, sessionAffinityId);
    }

    @Override
    public String toString() {
        return "PromptCacheOptions[retention=" + retention
                + ", cacheKey=" + present(cacheKey)
                + ", sessionAffinityId=" + present(sessionAffinityId)
                + "]";
    }

    private static String present(String value) {
        return value != null ? "present" : "absent";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
