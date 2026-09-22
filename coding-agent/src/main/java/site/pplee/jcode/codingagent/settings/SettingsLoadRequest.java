package site.pplee.jcode.codingagent.settings;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Explicit paths and overrides for one settings resolution. */
public record SettingsLoadRequest(
        Path workingDirectory,
        Optional<Path> userConfigDirectory,
        ProjectTrustDecision projectTrust,
        SettingsOverrides overrides,
        ObjectMapper objectMapper
) {
    public SettingsLoadRequest {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        workingDirectory = workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(workingDirectory)) {
            throw new IllegalArgumentException("workingDirectory must be an existing directory");
        }
        Objects.requireNonNull(userConfigDirectory, "userConfigDirectory must not be null");
        userConfigDirectory = userConfigDirectory.map(path -> path.toAbsolutePath().normalize());
        projectTrust = projectTrust == null ? ProjectTrustDecision.UNSPECIFIED : projectTrust;
        overrides = overrides == null ? SettingsOverrides.none() : overrides;
        objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public static SettingsLoadRequest explicit(
            Path workingDirectory,
            Path userConfigDirectory,
            ProjectTrustDecision projectTrust,
            SettingsOverrides overrides,
            ObjectMapper objectMapper
    ) {
        return new SettingsLoadRequest(
                workingDirectory,
                Optional.ofNullable(userConfigDirectory),
                projectTrust,
                overrides,
                objectMapper);
    }
}
