package site.pplee.jcode.ai.provider;

import java.util.Objects;

/**
 * One provider's refresh failure, recorded without letting the refresh
 * future fail as a whole.
 */
public record ModelsRefreshFailure(
        String providerId,
        String message
) {
    public ModelsRefreshFailure {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
