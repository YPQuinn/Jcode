package site.pplee.jcode.ai.model;

import java.util.Objects;

/**
 * Resolved model identity: the provider that serves it, the API dialect it
 * speaks, the model id, and a display name. Provider, API, and model id are
 * three independent dimensions — a provider may serve multiple API dialects
 * and the same model id may be reachable through different providers.
 *
 * <p>{@code Model} is a fully resolved identity (used at the model-call seam);
 * {@link ModelRef} is the lightweight stable reference carried in requests.
 */
public record Model(
        String provider,
        String api,
        String modelId,
        String name
) {
    public Model {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(api, "api must not be null");
        Objects.requireNonNull(modelId, "modelId must not be null");
        Objects.requireNonNull(name, "name must not be null");

        if (provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (api.isBlank()) {
            throw new IllegalArgumentException("api must not be blank");
        }
        if (modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
    }

    /** A {@link ModelRef} pointing at this model. */
    public ModelRef toRef() {
        return new ModelRef(provider, api, modelId);
    }
}
