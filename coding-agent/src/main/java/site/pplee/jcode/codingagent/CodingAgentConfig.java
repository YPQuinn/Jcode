package site.pplee.jcode.codingagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.codingagent.context.ProjectContextConfig;
import site.pplee.jcode.codingagent.event.CodingAgentEventSink;
import site.pplee.jcode.codingagent.tool.CodingToolConfig;
import site.pplee.jcode.codingagent.settings.CompactionSettings;
import site.pplee.jcode.codingagent.model.ModelProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Map;

/** Explicit immutable configuration used to create one coding-agent session. */
public record CodingAgentConfig(
        Path workingDirectory,
        ModelRef model,
        ModelClient modelClient,
        ObjectMapper objectMapper,
        ThinkingLevel thinkingLevel,
        ModelRequestOptions requestOptions,
        QueueMode steeringMode,
        QueueMode followUpMode,
        String customSystemPrompt,
        String appendSystemPrompt,
        CodingAgentEventSink eventSink,
        Clock clock,
        CodingToolConfig tools,
        ProjectContextConfig projectContext,
        CompactionSettings compaction,
        Map<ModelRef, ModelProfile> modelProfiles,
        CustomizationConfig customization,
        InputDeliveryMode inputDeliveryMode
) {
    public CodingAgentConfig {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        workingDirectory = workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(workingDirectory)) {
            throw new IllegalArgumentException("workingDirectory must be an existing directory");
        }
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(modelClient, "modelClient must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        thinkingLevel = thinkingLevel == null ? ThinkingLevel.PROVIDER_DEFAULT : thinkingLevel;
        requestOptions = requestOptions == null ? ModelRequestOptions.defaults() : requestOptions;
        steeringMode = steeringMode == null ? QueueMode.ONE_AT_A_TIME : steeringMode;
        followUpMode = followUpMode == null ? QueueMode.ONE_AT_A_TIME : followUpMode;
        eventSink = eventSink == null ? CodingAgentEventSink.noop() : eventSink;
        clock = clock == null ? Clock.systemUTC() : clock;
        tools = tools == null ? CodingToolConfig.readOnly() : tools;
        projectContext = projectContext == null ? ProjectContextConfig.disabled() : projectContext;
        compaction = compaction == null ? CompactionSettings.disabled() : compaction;
        modelProfiles = Map.copyOf(modelProfiles == null ? Map.of() : modelProfiles);
        customization = customization == null ? CustomizationConfig.none() : customization;
        inputDeliveryMode = inputDeliveryMode == null
                ? InputDeliveryMode.LEGACY_SESSION_QUEUE : inputDeliveryMode;
    }

    /** Compatibility constructor preserving the original session-queue semantics. */
    public CodingAgentConfig(
            Path workingDirectory,
            ModelRef model,
            ModelClient modelClient,
            ObjectMapper objectMapper,
            ThinkingLevel thinkingLevel,
            ModelRequestOptions requestOptions,
            QueueMode steeringMode,
            QueueMode followUpMode,
            String customSystemPrompt,
            String appendSystemPrompt,
            CodingAgentEventSink eventSink,
            Clock clock,
            CodingToolConfig tools,
            ProjectContextConfig projectContext,
            CompactionSettings compaction,
            Map<ModelRef, ModelProfile> modelProfiles,
            CustomizationConfig customization
    ) {
        this(workingDirectory, model, modelClient, objectMapper, thinkingLevel, requestOptions,
                steeringMode, followUpMode, customSystemPrompt, appendSystemPrompt, eventSink,
                clock, tools, projectContext, compaction, modelProfiles, customization,
                InputDeliveryMode.LEGACY_SESSION_QUEUE);
    }

    /** Compatibility constructor retaining the pre-customization configuration shape. */
    public CodingAgentConfig(
            Path workingDirectory,
            ModelRef model,
            ModelClient modelClient,
            ObjectMapper objectMapper,
            ThinkingLevel thinkingLevel,
            ModelRequestOptions requestOptions,
            QueueMode steeringMode,
            QueueMode followUpMode,
            String customSystemPrompt,
            String appendSystemPrompt,
            CodingAgentEventSink eventSink,
            Clock clock,
            CodingToolConfig tools,
            ProjectContextConfig projectContext,
            CompactionSettings compaction,
            Map<ModelRef, ModelProfile> modelProfiles
    ) {
        this(workingDirectory, model, modelClient, objectMapper, thinkingLevel, requestOptions,
                steeringMode, followUpMode, customSystemPrompt, appendSystemPrompt, eventSink,
                clock, tools, projectContext, compaction, modelProfiles,
                CustomizationConfig.none());
    }

    /** Compatibility constructor retaining the pre-compaction configuration shape. */
    public CodingAgentConfig(
            Path workingDirectory,
            ModelRef model,
            ModelClient modelClient,
            ObjectMapper objectMapper,
            ThinkingLevel thinkingLevel,
            ModelRequestOptions requestOptions,
            QueueMode steeringMode,
            QueueMode followUpMode,
            String customSystemPrompt,
            String appendSystemPrompt,
            CodingAgentEventSink eventSink,
            Clock clock,
            CodingToolConfig tools,
            ProjectContextConfig projectContext
    ) {
        this(workingDirectory, model, modelClient, objectMapper, thinkingLevel, requestOptions,
                steeringMode, followUpMode, customSystemPrompt, appendSystemPrompt, eventSink,
                clock, tools, projectContext, CompactionSettings.disabled(), Map.of());
    }

    /** Compatibility constructor that preserves explicit tool configuration. */
    public CodingAgentConfig(
            Path workingDirectory,
            ModelRef model,
            ModelClient modelClient,
            ObjectMapper objectMapper,
            ThinkingLevel thinkingLevel,
            ModelRequestOptions requestOptions,
            QueueMode steeringMode,
            QueueMode followUpMode,
            String customSystemPrompt,
            String appendSystemPrompt,
            CodingAgentEventSink eventSink,
            Clock clock,
            CodingToolConfig tools
    ) {
        this(workingDirectory, model, modelClient, objectMapper, thinkingLevel,
                requestOptions, steeringMode, followUpMode, customSystemPrompt,
                appendSystemPrompt, eventSink, clock, tools, ProjectContextConfig.disabled(),
                CompactionSettings.disabled(), Map.of());
    }

    /** Compatibility constructor that preserves the original read-only tool set. */
    public CodingAgentConfig(
            Path workingDirectory,
            ModelRef model,
            ModelClient modelClient,
            ObjectMapper objectMapper,
            ThinkingLevel thinkingLevel,
            ModelRequestOptions requestOptions,
            QueueMode steeringMode,
            QueueMode followUpMode,
            String customSystemPrompt,
            String appendSystemPrompt,
            CodingAgentEventSink eventSink,
            Clock clock
    ) {
        this(workingDirectory, model, modelClient, objectMapper, thinkingLevel,
                requestOptions, steeringMode, followUpMode, customSystemPrompt,
                appendSystemPrompt, eventSink, clock, CodingToolConfig.readOnly(),
                ProjectContextConfig.disabled(), CompactionSettings.disabled(), Map.of());
    }

    @Override
    public String toString() {
        return "CodingAgentConfig[workingDirectory=" + workingDirectory
                + ", model=" + model
                + ", modelClient=" + modelClient.getClass().getName()
                + ", objectMapper=" + objectMapper.getClass().getName()
                + ", thinkingLevel=" + thinkingLevel
                + ", requestOptions=present"
                + ", steeringMode=" + steeringMode
                + ", followUpMode=" + followUpMode
                + ", customSystemPrompt=" + (customSystemPrompt == null ? "absent" : "present")
                + ", appendSystemPrompt=" + (appendSystemPrompt == null ? "absent" : "present")
                + ", eventSink=" + eventSink.getClass().getName()
                + ", clock=" + clock.getClass().getName()
                + ", tools=" + tools
                + ", projectContext=" + projectContext
                + ", compaction=" + compaction
                + ", modelProfiles=" + modelProfiles.size()
                + ", customization=" + customization
                + ", inputDeliveryMode=" + inputDeliveryMode + ']';
    }
}
