package site.pplee.jcode.ai.model;

import java.util.Objects;

/**
 * Lightweight, stable reference to a model along three independent dimensions:
 * the serving {@code provider}, the API {@code dialect}, and the {@code modelId}.
 *
 * <p>Unlike the old {@code (provider, modelId)} pair, {@code api} is a separate
 * dimension so the same provider/api/model triple is never ambiguous (a
 * provider may expose one model through more than one API dialect).
 *
 * <p>Carried in {@link site.pplee.jcode.ai.client.ModelRequest}; a fully
 * resolved {@link Model} is reconstructed by a {@code Provider}/{@code Models}
 * runtime (in the {@code ai-providers} module) and is not needed by the
 * loop.
 */
public record ModelRef(
        String provider,
        String api,
        String modelId
) {
    public ModelRef {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(api, "api must not be null");
        Objects.requireNonNull(modelId, "modelId must not be null");

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

    /** Build a fully-resolved {@link Model} with a display name (defaults to the id). */
    public Model toModel(String name) {
        return new Model(provider, api, modelId, name);
    }

    /** Convenience: resolve to a {@link Model} whose display name equals the id. */
    public Model toModel() {
        return toModel(modelId);
    }
}
