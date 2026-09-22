package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.model.ModelAssemblyDiagnostic;
import site.pplee.jcode.codingagent.model.ModelCatalogSnapshot;
import site.pplee.jcode.codingagent.model.ModelSelection;
import site.pplee.jcode.codingagent.settings.ResolvedSettings;
import site.pplee.jcode.codingagent.settings.SettingsDiagnostic;
import site.pplee.jcode.codingagent.settings.SettingsLoadResult;

import java.util.List;
import java.util.Objects;

/** Created session plus immutable, non-secret settings and model assembly details. */
public record SessionCreationResult(
        CodingAgentSession session,
        SettingsLoadResult settingsResolution,
        ModelSelection selection,
        ModelCatalogSnapshot catalog,
        List<ModelAssemblyDiagnostic> modelDiagnostics
) {
    public SessionCreationResult {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(settingsResolution, "settingsResolution must not be null");
        Objects.requireNonNull(selection, "selection must not be null");
        Objects.requireNonNull(catalog, "catalog must not be null");
        modelDiagnostics = List.copyOf(
                Objects.requireNonNull(modelDiagnostics, "modelDiagnostics must not be null"));
    }

    public ResolvedSettings settings() {
        return settingsResolution.settings();
    }

    public List<SettingsDiagnostic> settingsDiagnostics() {
        return settingsResolution.diagnostics();
    }
}
