package site.pplee.jcode.ai.provider;

import java.util.List;
import java.util.Objects;

/**
 * Result of a {@link Models#refresh(ModelsRefreshContext)} pass: the
 * per-provider failures (empty on success). The future never completes
 * exceptionally because of a provider failure.
 */
public record ModelsRefreshResult(List<ModelsRefreshFailure> failures) {
    public ModelsRefreshResult {
        failures = List.copyOf(Objects.requireNonNull(failures, "failures must not be null"));
    }

    /** True when every provider refreshed without failure. */
    public boolean successful() {
        return failures.isEmpty();
    }
}
