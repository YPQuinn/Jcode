package site.pplee.jcode.agentcore.model;

import java.util.Objects;

public record ModelRef(
        String provider,
        String modelId
) {
    public ModelRef {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(modelId, "modelId must not be null");

        if (provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }

        if (modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
    }
}