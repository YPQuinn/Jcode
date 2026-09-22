package site.pplee.jcode.codingagent.model;

import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.provider.Models;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable local catalog view; creating it performs no remote authentication check. */
public record ModelCatalogSnapshot(List<Entry> entries) {
    public ModelCatalogSnapshot {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
    }

    public static ModelCatalogSnapshot capture(
            Models models,
            Map<ModelRef, ModelProfile> profiles
    ) {
        Objects.requireNonNull(models, "models must not be null");
        Objects.requireNonNull(profiles, "profiles must not be null");
        var entries = new ArrayList<Entry>();
        for (Model model : models.models()) {
            var provider = models.provider(model.provider()).orElseThrow();
            entries.add(new Entry(
                    model,
                    provider.supports(model.toRef()),
                    provider.auth().isConfigured(),
                    profiles.getOrDefault(model.toRef(), ModelProfile.empty())));
        }
        return new ModelCatalogSnapshot(entries);
    }

    /** Advertised model plus distinct support, auth, and product-profile state. */
    public record Entry(
            Model model,
            boolean supported,
            boolean authConfigured,
            ModelProfile profile
    ) {
        public Entry {
            Objects.requireNonNull(model, "model must not be null");
            Objects.requireNonNull(profile, "profile must not be null");
        }
    }
}
