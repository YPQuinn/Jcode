package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.model.ModelAssemblyDiagnostic;
import site.pplee.jcode.codingagent.settings.SettingsDiagnostic;

import java.util.List;
import java.util.Objects;

/** Checked settings/provider assembly failure with non-secret diagnostics. */
public final class SessionAssemblyException extends Exception {
    private final List<SettingsDiagnostic> settingsDiagnostics;
    private final List<ModelAssemblyDiagnostic> modelDiagnostics;

    public SessionAssemblyException(
            String message,
            List<SettingsDiagnostic> settingsDiagnostics,
            List<ModelAssemblyDiagnostic> modelDiagnostics
    ) {
        super(message);
        this.settingsDiagnostics = List.copyOf(
                Objects.requireNonNull(settingsDiagnostics, "settingsDiagnostics must not be null"));
        this.modelDiagnostics = List.copyOf(
                Objects.requireNonNull(modelDiagnostics, "modelDiagnostics must not be null"));
    }

    public List<SettingsDiagnostic> settingsDiagnostics() {
        return settingsDiagnostics;
    }

    public List<ModelAssemblyDiagnostic> modelDiagnostics() {
        return modelDiagnostics;
    }
}
