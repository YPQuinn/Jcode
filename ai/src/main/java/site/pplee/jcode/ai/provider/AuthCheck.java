package site.pplee.jcode.ai.provider;

import java.util.Objects;

/**
 * Snapshot of a provider's auth status for diagnostics. Carries no secrets:
 * only whether auth is configured and a non-secret diagnostic string.
 */
public record AuthCheck(
        String providerId,
        boolean configured,
        String diagnostic
) {
    public AuthCheck {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(diagnostic, "diagnostic must not be null");
    }
}
