package site.pplee.jcode.codingagent.model;

import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.provider.Models;
import site.pplee.jcode.codingagent.settings.ResolvedSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic local model selection for new and restored sessions. */
public final class ModelSelector {
    private ModelSelector() {
    }

    public static ModelSelection selectNew(
            ResolvedSettings settings,
            Models models
    ) throws ModelSelectionException {
        return select(settings, models, null, null);
    }

    public static ModelSelection selectRestored(
            ResolvedSettings settings,
            Models models,
            ModelRef historicalModel,
            ThinkingLevel historicalThinking
    ) throws ModelSelectionException {
        return select(settings, models, historicalModel, historicalThinking);
    }

    private static ModelSelection select(
            ResolvedSettings settings,
            Models models,
            ModelRef historicalModel,
            ThinkingLevel historicalThinking
    ) throws ModelSelectionException {
        Objects.requireNonNull(settings, "settings must not be null");
        Objects.requireNonNull(models, "models must not be null");
        var diagnostics = new ArrayList<ModelAssemblyDiagnostic>();
        Optional<ModelRef> configured = settings.defaultModel();
        Optional<ModelRef> restored = Optional.ofNullable(historicalModel);

        if (settings.sdkModelOverride()) {
            ModelRef requested = configured.orElseThrow();
            requireAvailable(requested, models, diagnostics);
            return selection(configured, restored, requested,
                    settings.defaultThinkingLevel(), ModelSelection.Source.SDK, diagnostics);
        }

        if (historicalModel != null) {
            if (isAvailable(historicalModel, models, diagnostics)) {
                ThinkingLevel thinking = settings.sdkThinkingOverride()
                        ? settings.defaultThinkingLevel()
                        : historicalThinking == null
                                ? settings.defaultThinkingLevel() : historicalThinking;
                return selection(configured, restored, historicalModel, thinking,
                        ModelSelection.Source.HISTORY, diagnostics);
            }
            diagnostics.add(ModelAssemblyDiagnostic.provider(
                    ModelAssemblyDiagnostic.Code.HISTORICAL_MODEL_UNAVAILABLE,
                    "historical model is unavailable; attempting the configured default",
                    historicalModel.provider()));
            if (configured.isPresent() && !configured.orElseThrow().equals(historicalModel)) {
                ModelRef fallback = configured.orElseThrow();
                requireAvailable(fallback, models, diagnostics);
                diagnostics.add(ModelAssemblyDiagnostic.provider(
                        ModelAssemblyDiagnostic.Code.FALLBACK_SELECTED,
                        "configured default selected because the historical model is unavailable",
                        fallback.provider()));
                return selection(configured, restored, fallback,
                        settings.defaultThinkingLevel(), ModelSelection.Source.DEFAULT, diagnostics);
            }
            throw new ModelSelectionException(
                    "historical model is unavailable and no distinct usable default is configured",
                    diagnostics);
        }

        if (configured.isEmpty()) {
            throw new ModelSelectionException(
                    "no model was selected; configure defaultModel or an SDK override",
                    diagnostics);
        }
        ModelRef selected = configured.orElseThrow();
        requireAvailable(selected, models, diagnostics);
        return selection(configured, restored, selected, settings.defaultThinkingLevel(),
                ModelSelection.Source.DEFAULT, diagnostics);
    }

    private static ModelSelection selection(
            Optional<ModelRef> requested,
            Optional<ModelRef> restored,
            ModelRef selected,
            ThinkingLevel thinking,
            ModelSelection.Source source,
            List<ModelAssemblyDiagnostic> diagnostics
    ) {
        return new ModelSelection(
                requested, restored, selected, thinking, source, diagnostics);
    }

    private static void requireAvailable(
            ModelRef ref,
            Models models,
            List<ModelAssemblyDiagnostic> diagnostics
    ) throws ModelSelectionException {
        if (!isAvailable(ref, models, diagnostics)) {
            throw new ModelSelectionException(
                    "selected model is not locally available: " + ref.provider()
                            + "/" + ref.api() + "/" + ref.modelId(),
                    diagnostics);
        }
    }

    private static boolean isAvailable(
            ModelRef ref,
            Models models,
            List<ModelAssemblyDiagnostic> diagnostics
    ) {
        var provider = models.provider(ref.provider());
        if (provider.isEmpty()) {
            diagnostics.add(ModelAssemblyDiagnostic.provider(
                    ModelAssemblyDiagnostic.Code.PROVIDER_NOT_ASSEMBLED,
                    "provider is not assembled for model " + ref.modelId(),
                    ref.provider()));
            return false;
        }
        if (!provider.orElseThrow().supports(ref)) {
            diagnostics.add(ModelAssemblyDiagnostic.provider(
                    ModelAssemblyDiagnostic.Code.MODEL_UNSUPPORTED,
                    "provider does not support model " + ref.modelId(),
                    ref.provider()));
            return false;
        }
        if (!provider.orElseThrow().auth().isConfigured()) {
            diagnostics.add(ModelAssemblyDiagnostic.provider(
                    ModelAssemblyDiagnostic.Code.MODEL_AUTH_UNCONFIGURED,
                    "provider authentication is not configured",
                    ref.provider()));
            return false;
        }
        return true;
    }
}
