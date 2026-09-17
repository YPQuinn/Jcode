package site.pplee.jcode.aiproviders.openai;

/**
 * Explicit Responses endpoint profile for session-affinity headers.
 * Callers choose a profile; the adapter never infers one from a URL.
 */
public enum OpenAiEndpointProfile {
    /**
     * Official OpenAI session shape: {@code session_id} plus
     * {@code x-client-request-id}.
     */
    OPENAI,
    /**
     * OpenAI-compatible endpoints that reject {@code session_id} but still
     * accept {@code x-client-request-id}.
     */
    OPENAI_NO_SESSION,
    /** OpenRouter-compatible session header: {@code x-session-id}. */
    OPENROUTER
}
