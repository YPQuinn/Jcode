package site.pplee.jcode.codingagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.codingagent.event.CodingAgentEventSink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

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
        Clock clock
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
                + ", clock=" + clock.getClass().getName() + ']';
    }
}
