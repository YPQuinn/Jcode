package site.pplee.jcode.aiproviders.openai;

import site.pplee.jcode.ai.provider.ProviderAuth;

import java.util.Optional;

/**
 * Provider-owned auth status for explicit API-key credentials. The key value
 * itself stays inside {@link OpenAiCredentials}; this type only reports
 * whether auth is configured, with a non-secret diagnostic.
 */
final class OpenAiApiKeyAuth implements ProviderAuth {
    private static final OpenAiApiKeyAuth CONFIGURED = new OpenAiApiKeyAuth();

    private OpenAiApiKeyAuth() {
    }

    static OpenAiApiKeyAuth configured() {
        return CONFIGURED;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public Optional<String> diagnostic() {
        return Optional.empty();
    }
}
