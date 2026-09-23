package site.pplee.jcode.codingagent.resource;

import site.pplee.jcode.codingagent.settings.ProjectTrustDecision;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Explicit file-resource discovery inputs. No path is inferred from the process home. */
public final class ResourceConfig {
    private final boolean enabled;
    private final boolean includeDefaults;
    private final Path userDirectory;
    private final List<Path> skillPaths;
    private final List<Path> promptPaths;
    private final Path systemPromptFile;
    private final Path appendSystemPromptFile;
    private final ProjectTrustDecision projectTextResources;
    private final Path trustStoreDirectory;

    private ResourceConfig(Builder builder) {
        enabled = builder.enabled;
        includeDefaults = builder.includeDefaults;
        userDirectory = absolute(builder.userDirectory);
        skillPaths = copyPaths(builder.skillPaths);
        promptPaths = copyPaths(builder.promptPaths);
        systemPromptFile = absolute(builder.systemPromptFile);
        appendSystemPromptFile = absolute(builder.appendSystemPromptFile);
        projectTextResources = builder.projectTextResources;
        trustStoreDirectory = absolute(builder.trustStoreDirectory);
    }

    public static ResourceConfig disabled() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean includeDefaults() {
        return includeDefaults;
    }

    public Optional<Path> userDirectory() {
        return Optional.ofNullable(userDirectory);
    }

    public List<Path> skillPaths() {
        return skillPaths;
    }

    public List<Path> promptPaths() {
        return promptPaths;
    }

    public Optional<Path> systemPromptFile() {
        return Optional.ofNullable(systemPromptFile);
    }

    public Optional<Path> appendSystemPromptFile() {
        return Optional.ofNullable(appendSystemPromptFile);
    }

    public ProjectTrustDecision projectTextResources() {
        return projectTextResources;
    }

    public Optional<Path> trustStoreDirectory() {
        return Optional.ofNullable(trustStoreDirectory);
    }

    /** Apply the factory's explicit user config directory only to omitted resource inputs. */
    public ResourceConfig withUserConfigDirectory(Path directory) {
        if (directory == null || !enabled) {
            return this;
        }
        var builder = builder()
                .enabled(true)
                .includeDefaults(includeDefaults)
                .skillPaths(skillPaths)
                .promptPaths(promptPaths)
                .projectTextResources(projectTextResources);
        builder.userDirectory(userDirectory == null ? directory : userDirectory);
        builder.trustStoreDirectory(trustStoreDirectory == null ? directory : trustStoreDirectory);
        if (systemPromptFile != null) {
            builder.systemPromptFile(systemPromptFile);
        }
        if (appendSystemPromptFile != null) {
            builder.appendSystemPromptFile(appendSystemPromptFile);
        }
        return builder.build();
    }

    @Override
    public String toString() {
        return "ResourceConfig[enabled=" + enabled
                + ", includeDefaults=" + includeDefaults
                + ", userDirectory=" + (userDirectory == null ? "absent" : "present")
                + ", skillPaths=" + skillPaths.size()
                + ", promptPaths=" + promptPaths.size()
                + ", systemPromptFile=" + (systemPromptFile == null ? "absent" : "present")
                + ", appendSystemPromptFile="
                + (appendSystemPromptFile == null ? "absent" : "present")
                + ", projectTextResources=" + projectTextResources
                + ", trustStoreDirectory="
                + (trustStoreDirectory == null ? "absent" : "present") + ']';
    }

    private static Path absolute(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static List<Path> copyPaths(List<Path> paths) {
        Objects.requireNonNull(paths, "paths must not be null");
        if (paths.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("paths must not contain null");
        }
        return paths.stream().map(ResourceConfig::absolute).toList();
    }

    /** Builder whose defaults leave all new discovery disabled. */
    public static final class Builder {
        private boolean enabled;
        private boolean includeDefaults = true;
        private Path userDirectory;
        private List<Path> skillPaths = List.of();
        private List<Path> promptPaths = List.of();
        private Path systemPromptFile;
        private Path appendSystemPromptFile;
        private ProjectTrustDecision projectTextResources = ProjectTrustDecision.UNSPECIFIED;
        private Path trustStoreDirectory;

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder includeDefaults(boolean value) {
            includeDefaults = value;
            return this;
        }

        public Builder userDirectory(Path value) {
            userDirectory = Objects.requireNonNull(value, "userDirectory must not be null");
            return this;
        }

        public Builder skillPaths(List<Path> value) {
            skillPaths = List.copyOf(Objects.requireNonNull(value, "skillPaths must not be null"));
            return this;
        }

        public Builder promptPaths(List<Path> value) {
            promptPaths = List.copyOf(Objects.requireNonNull(value, "promptPaths must not be null"));
            return this;
        }

        public Builder systemPromptFile(Path value) {
            systemPromptFile = Objects.requireNonNull(value, "systemPromptFile must not be null");
            return this;
        }

        public Builder appendSystemPromptFile(Path value) {
            appendSystemPromptFile = Objects.requireNonNull(value, "appendSystemPromptFile must not be null");
            return this;
        }

        public Builder projectTextResources(ProjectTrustDecision value) {
            projectTextResources = Objects.requireNonNull(value, "projectTextResources must not be null");
            return this;
        }

        public Builder trustStoreDirectory(Path value) {
            trustStoreDirectory = Objects.requireNonNull(value, "trustStoreDirectory must not be null");
            return this;
        }

        public ResourceConfig build() {
            return new ResourceConfig(this);
        }
    }
}
