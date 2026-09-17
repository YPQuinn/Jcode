package site.pplee.jcode.aiproviders.openai;

import java.util.Objects;

/**
 * Endpoint-level Responses compatibility. These flags describe what the
 * HTTP endpoint accepts, not what a particular model prefers. Callers set
 * this explicitly; the adapter never infers compatibility from a URL.
 */
public record OpenAiResponsesCompatibility(
        boolean developerRole,
        OpenAiEndpointProfile endpointProfile,
        boolean maxOutputTokens,
        boolean promptCacheKey,
        boolean longCacheRetention,
        boolean strictTools,
        boolean grammarTools
) {
    public OpenAiResponsesCompatibility {
        Objects.requireNonNull(endpointProfile, "endpointProfile must not be null");
    }

    /**
     * Compatibility constructor from the request-control batch. Strict and
     * grammar tools stay off so a custom endpoint remains conservative.
     */
    public OpenAiResponsesCompatibility(
            boolean developerRole,
            OpenAiEndpointProfile endpointProfile,
            boolean maxOutputTokens,
            boolean promptCacheKey,
            boolean longCacheRetention
    ) {
        this(developerRole, endpointProfile, maxOutputTokens, promptCacheKey, longCacheRetention, false, false);
    }

    /**
     * Compatibility constructor: role only. Extra request-control, cache,
     * strict, and grammar capabilities stay off so a custom endpoint remains
     * conservative.
     */
    public OpenAiResponsesCompatibility(boolean developerRole) {
        this(developerRole, OpenAiEndpointProfile.OPENAI, false, false, false, false, false);
    }

    /**
     * Official OpenAI Responses defaults: developer role, official session
     * headers, max-output-token clamp, cache key, long retention, strict
     * function tools, and grammar/custom tools.
     */
    public static OpenAiResponsesCompatibility openai() {
        return new OpenAiResponsesCompatibility(
                true, OpenAiEndpointProfile.OPENAI, true, true, true, true, true);
    }

    /**
     * Official OpenAI cache, session, strict, and grammar behavior that
     * still forces {@code system} even when a model prefers {@code developer}.
     */
    public static OpenAiResponsesCompatibility forceSystem() {
        return new OpenAiResponsesCompatibility(
                false, OpenAiEndpointProfile.OPENAI, true, true, true, true, true);
    }

    /**
     * Official OpenAI request controls without the {@code session_id}
     * header. {@code x-client-request-id} may still be sent.
     */
    public static OpenAiResponsesCompatibility openaiNoSession() {
        return new OpenAiResponsesCompatibility(
                true, OpenAiEndpointProfile.OPENAI_NO_SESSION, true, true, true, true, true);
    }

    /**
     * OpenRouter-compatible session header. Long cache retention, strict
     * tools, and grammar/custom tools stay off until the caller enables
     * them explicitly.
     */
    public static OpenAiResponsesCompatibility openRouter() {
        return new OpenAiResponsesCompatibility(
                false, OpenAiEndpointProfile.OPENROUTER, true, true, false, false, false);
    }
}
