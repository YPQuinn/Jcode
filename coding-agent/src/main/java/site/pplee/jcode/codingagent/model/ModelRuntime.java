package site.pplee.jcode.codingagent.model;

import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.provider.Models;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Models router, product profiles, diagnostics, and optional owned provider resources. */
public record ModelRuntime(
        Models models,
        Map<ModelRef, ModelProfile> profiles,
        List<ModelAssemblyDiagnostic> diagnostics,
        Optional<AutoCloseable> ownedResources
) {
    public ModelRuntime {
        Objects.requireNonNull(models, "models must not be null");
        profiles = Map.copyOf(Objects.requireNonNull(profiles, "profiles must not be null"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
        Objects.requireNonNull(ownedResources, "ownedResources must not be null");
    }

    public ModelCatalogSnapshot catalog() {
        return ModelCatalogSnapshot.capture(models, profiles);
    }
}
