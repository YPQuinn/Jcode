package site.pplee.jcode.ai.provider;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.util.Objects;

/**
 * Context for {@link ModelProvider#refreshModels(ModelsRefreshContext)}:
 * carries the shared read-only cancellation signal so a refresh can stop
 * cooperatively.
 */
public record ModelsRefreshContext(CancellationSignal cancellation) {
    public ModelsRefreshContext {
        Objects.requireNonNull(cancellation, "cancellation must not be null");
    }
}
