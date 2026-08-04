package site.pplee.jcode.aiproviders.openai;

import java.util.Objects;

/**
 * Redacted holder for OpenAI authentication material. Created explicitly by
 * the caller; this module never reads environment variables or configuration
 * files. The API key is not exposed by any public accessor and never appears
 * in {@link #toString()}.
 */
public final class OpenAiCredentials {
    private final String apiKey;

    private OpenAiCredentials(String apiKey) {
        this.apiKey = apiKey;
    }

    /** Explicit API-key credentials. Rejects blank keys. */
    public static OpenAiCredentials apiKey(String apiKey) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        return new OpenAiCredentials(apiKey);
    }

    /** Package-private access; the key never leaves this module. */
    String apiKey() {
        return apiKey;
    }

    @Override
    public String toString() {
        return "OpenAiCredentials[apiKey=***redacted***]";
    }
}
