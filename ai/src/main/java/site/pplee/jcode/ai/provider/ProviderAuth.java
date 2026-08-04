package site.pplee.jcode.ai.provider;

import java.util.Objects;
import java.util.Optional;

/**
 * Provider-owned authentication status. Reports only whether auth is fully
 * configured and a non-secret diagnostic; resolved headers, tokens, API keys
 * and credential stores stay inside the provider implementation.
 */
public interface ProviderAuth {
    /** True once the provider has complete auth configuration. */
    boolean isConfigured();

    /** Non-secret diagnostic for unconfigured auth; empty when configured. */
    Optional<String> diagnostic();

    /** Simple explicit auth status value. */
    static ProviderAuth of(boolean configured, String diagnostic) {
        return new SimpleAuth(configured, diagnostic);
    }

    /** Immutable value implementation of {@link ProviderAuth}. */
    record SimpleAuth(boolean configured, String diagnosticText) implements ProviderAuth {
        public SimpleAuth {
            Objects.requireNonNull(diagnosticText, "diagnosticText must not be null");
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public Optional<String> diagnostic() {
            return diagnosticText.isEmpty() ? Optional.empty() : Optional.of(diagnosticText);
        }
    }
}
