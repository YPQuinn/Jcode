package site.pplee.jcode.ai.client;

/**
 * Provider-neutral prompt-cache retention intent. Adapters map these values
 * to vendor fields only when the endpoint capability allows it.
 */
public enum CacheRetention {
    /**
     * Do not override the provider's default retention. An explicit cache
     * key is still forwarded when the endpoint accepts one.
     */
    PROVIDER_DEFAULT,
    /** Do not send a cache key or session-affinity headers. */
    NONE,
    /** Request the provider's short-lived cache retention. */
    SHORT,
    /** Request the provider's long-lived cache retention. */
    LONG
}
