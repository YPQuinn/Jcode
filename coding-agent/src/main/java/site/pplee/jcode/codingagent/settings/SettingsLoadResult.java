package site.pplee.jcode.codingagent.settings;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Resolved settings plus non-secret load and project-trust diagnostics. */
public record SettingsLoadResult(
        ResolvedSettings settings,
        Path canonicalProject,
        ProjectTrustDecision projectTrust,
        ProjectTrustSource projectTrustSource,
        boolean projectSettingsApplied,
        List<SettingsDiagnostic> diagnostics
) {
    public SettingsLoadResult {
        Objects.requireNonNull(settings, "settings must not be null");
        Objects.requireNonNull(canonicalProject, "canonicalProject must not be null");
        canonicalProject = canonicalProject.toAbsolutePath().normalize();
        Objects.requireNonNull(projectTrust, "projectTrust must not be null");
        Objects.requireNonNull(projectTrustSource, "projectTrustSource must not be null");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }
}
