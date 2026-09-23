package site.pplee.jcode.codingagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.provider.Models;
import site.pplee.jcode.aiproviders.openai.OpenAiCredentials;
import site.pplee.jcode.codingagent.context.ProjectContextConfig;
import site.pplee.jcode.codingagent.event.CodingAgentEventSink;
import site.pplee.jcode.codingagent.model.ModelProfile;
import site.pplee.jcode.codingagent.model.ProviderDefinition;
import site.pplee.jcode.codingagent.settings.ProjectTrustDecision;
import site.pplee.jcode.codingagent.settings.SettingsOverrides;
import site.pplee.jcode.codingagent.tool.CodingToolConfig;
import site.pplee.jcode.codingagent.extension.CodingExtension;
import site.pplee.jcode.codingagent.resource.ResourceConfig;

import site.pplee.jcode.ai.model.ModelRef;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Explicit inputs for settings-driven session assembly. */
public final class CodingAgentSessionOptions {
    private final Path workingDirectory;
    private final Path userConfigDirectory;
    private final ProjectTrustDecision projectTrust;
    private final SettingsOverrides settingsOverrides;
    private final Models borrowedModels;
    private final Map<ModelRef, ModelProfile> borrowedProfiles;
    private final List<ProviderDefinition> providerDefinitions;
    private final Map<String, OpenAiCredentials> credentials;
    private final boolean environmentEnabled;
    private final Function<String, String> environment;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final CodingAgentEventSink eventSink;
    private final String customSystemPrompt;
    private final String appendSystemPrompt;
    private final CodingToolConfig tools;
    private final boolean toolsExplicitlyConfigured;
    private final ProjectContextConfig projectContext;
    private final CustomizationConfig customization;

    private CodingAgentSessionOptions(Builder builder) {
        workingDirectory = builder.workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(workingDirectory)) {
            throw new IllegalArgumentException("workingDirectory must be an existing directory");
        }
        userConfigDirectory = builder.userConfigDirectory == null
                ? null : builder.userConfigDirectory.toAbsolutePath().normalize();
        projectTrust = builder.projectTrust;
        settingsOverrides = builder.settingsOverrides;
        borrowedModels = builder.borrowedModels;
        borrowedProfiles = Map.copyOf(builder.borrowedProfiles);
        providerDefinitions = List.copyOf(builder.providerDefinitions);
        credentials = Map.copyOf(builder.credentials);
        environmentEnabled = builder.environmentEnabled;
        environment = builder.environment;
        objectMapper = builder.objectMapper;
        clock = builder.clock;
        eventSink = builder.eventSink;
        customSystemPrompt = builder.customSystemPrompt;
        appendSystemPrompt = builder.appendSystemPrompt;
        tools = builder.tools;
        toolsExplicitlyConfigured = builder.toolsExplicitlyConfigured;
        projectContext = builder.projectContext;
        var configuredCustomization = builder.customization;
        customization = new CustomizationConfig(
                configuredCustomization.resources().withUserConfigDirectory(userConfigDirectory),
                configuredCustomization.extensions());
        if (borrowedModels != null
                && (!providerDefinitions.isEmpty() || !credentials.isEmpty())) {
            throw new IllegalArgumentException(
                    "borrowed Models cannot be combined with provider definitions or credentials");
        }
    }

    public static Builder builder(Path workingDirectory) {
        return new Builder(workingDirectory);
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public Optional<Path> userConfigDirectory() {
        return Optional.ofNullable(userConfigDirectory);
    }

    public ProjectTrustDecision projectTrust() {
        return projectTrust;
    }

    public SettingsOverrides settingsOverrides() {
        return settingsOverrides;
    }

    public Optional<Models> borrowedModels() {
        return Optional.ofNullable(borrowedModels);
    }

    public Map<ModelRef, ModelProfile> borrowedProfiles() {
        return borrowedProfiles;
    }

    public List<ProviderDefinition> providerDefinitions() {
        return providerDefinitions;
    }

    public Map<String, OpenAiCredentials> credentials() {
        return credentials;
    }

    public boolean environmentEnabled() {
        return environmentEnabled;
    }

    public Function<String, String> environment() {
        return environment;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public Clock clock() {
        return clock;
    }

    public CodingAgentEventSink eventSink() {
        return eventSink;
    }

    public String customSystemPrompt() {
        return customSystemPrompt;
    }

    public String appendSystemPrompt() {
        return appendSystemPrompt;
    }

    public CodingToolConfig tools() {
        return tools;
    }

    boolean toolsExplicitlyConfigured() {
        return toolsExplicitlyConfigured;
    }

    public ProjectContextConfig projectContext() {
        return projectContext;
    }

    public CustomizationConfig customization() {
        return customization;
    }

    /** Builder that keeps every discovery source disabled until explicitly supplied. */
    public static final class Builder {
        private final Path workingDirectory;
        private Path userConfigDirectory;
        private ProjectTrustDecision projectTrust = ProjectTrustDecision.UNSPECIFIED;
        private SettingsOverrides settingsOverrides = SettingsOverrides.none();
        private Models borrowedModels;
        private Map<ModelRef, ModelProfile> borrowedProfiles = Map.of();
        private List<ProviderDefinition> providerDefinitions = List.of();
        private Map<String, OpenAiCredentials> credentials = Map.of();
        private boolean environmentEnabled;
        private Function<String, String> environment = ignored -> null;
        private ObjectMapper objectMapper = new ObjectMapper();
        private Clock clock = Clock.systemUTC();
        private CodingAgentEventSink eventSink = CodingAgentEventSink.noop();
        private String customSystemPrompt;
        private String appendSystemPrompt;
        private CodingToolConfig tools = CodingToolConfig.readOnly();
        private boolean toolsExplicitlyConfigured;
        private ProjectContextConfig projectContext = ProjectContextConfig.disabled();
        private CustomizationConfig customization = CustomizationConfig.none();

        private Builder(Path workingDirectory) {
            this.workingDirectory = Objects.requireNonNull(
                    workingDirectory, "workingDirectory must not be null");
        }

        public Builder userConfigDirectory(Path value) {
            userConfigDirectory = Objects.requireNonNull(value, "userConfigDirectory must not be null");
            return this;
        }

        public Builder projectTrust(ProjectTrustDecision value) {
            projectTrust = Objects.requireNonNull(value, "projectTrust must not be null");
            return this;
        }

        public Builder settingsOverrides(SettingsOverrides value) {
            settingsOverrides = Objects.requireNonNull(value, "settingsOverrides must not be null");
            return this;
        }

        public Builder borrowedModels(Models value, Map<ModelRef, ModelProfile> profiles) {
            borrowedModels = Objects.requireNonNull(value, "borrowedModels must not be null");
            borrowedProfiles = Map.copyOf(Objects.requireNonNull(profiles, "profiles must not be null"));
            return this;
        }

        public Builder providerDefinitions(List<ProviderDefinition> value) {
            providerDefinitions = List.copyOf(
                    Objects.requireNonNull(value, "providerDefinitions must not be null"));
            return this;
        }

        public Builder credentials(Map<String, OpenAiCredentials> value) {
            credentials = Map.copyOf(Objects.requireNonNull(value, "credentials must not be null"));
            return this;
        }

        public Builder environment(Function<String, String> value) {
            environmentEnabled = true;
            environment = Objects.requireNonNull(value, "environment must not be null");
            return this;
        }

        public Builder systemEnvironment() {
            return environment(System::getenv);
        }

        public Builder objectMapper(ObjectMapper value) {
            objectMapper = Objects.requireNonNull(value, "objectMapper must not be null");
            return this;
        }

        public Builder clock(Clock value) {
            clock = Objects.requireNonNull(value, "clock must not be null");
            return this;
        }

        public Builder eventSink(CodingAgentEventSink value) {
            eventSink = Objects.requireNonNull(value, "eventSink must not be null");
            return this;
        }

        public Builder customSystemPrompt(String value) {
            customSystemPrompt = value;
            return this;
        }

        public Builder appendSystemPrompt(String value) {
            appendSystemPrompt = value;
            return this;
        }

        /**
         * Supplies the complete SDK tool configuration. Its enabled-tool set
         * takes precedence over settings defaults, including when it is empty.
         */
        public Builder tools(CodingToolConfig value) {
            tools = Objects.requireNonNull(value, "tools must not be null");
            toolsExplicitlyConfigured = true;
            return this;
        }

        public Builder projectContext(ProjectContextConfig value) {
            projectContext = Objects.requireNonNull(value, "projectContext must not be null");
            return this;
        }

        public Builder customization(CustomizationConfig value) {
            customization = Objects.requireNonNull(value, "customization must not be null");
            return this;
        }

        public Builder resources(ResourceConfig value) {
            customization = new CustomizationConfig(
                    Objects.requireNonNull(value, "resources must not be null"),
                    customization.extensions());
            return this;
        }

        public Builder extensions(List<CodingExtension> value) {
            customization = new CustomizationConfig(
                    customization.resources(),
                    Objects.requireNonNull(value, "extensions must not be null"));
            return this;
        }

        public CodingAgentSessionOptions build() {
            return new CodingAgentSessionOptions(this);
        }
    }
}
