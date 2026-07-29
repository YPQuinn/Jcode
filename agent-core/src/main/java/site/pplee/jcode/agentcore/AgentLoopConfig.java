package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.model.AgentMessage;
import site.pplee.jcode.agentcore.model.ModelRef;
import site.pplee.jcode.agentcore.model.ToolExecutionMode;
import site.pplee.jcode.agentcore.queue.PendingMessageSource;
import site.pplee.jcode.agentcore.spi.LlmClient;
import site.pplee.jcode.agentcore.spi.LlmEventSink;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

/**
 * Per-run configuration assembled by {@link Agent}: model/client bindings,
 * shared {@link ObjectMapper}, tool execution mode, steering/follow-up
 * sources, and event sinks. Optional sources/sinks default to empty/noop.
 */
record AgentLoopConfig(
        ModelRef model,
        LlmClient llmClient,
        ObjectMapper objectMapper,
        ToolExecutionMode toolExecution,
        PendingMessageSource steeringMessages,
        PendingMessageSource followUpMessages,
        AgentEventSink eventSink,
        LlmEventSink llmEventSink
) {
    AgentLoopConfig {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(llmClient, "llmClient must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        toolExecution = (toolExecution == null) ? ToolExecutionMode.PARALLEL : toolExecution;
        steeringMessages = (steeringMessages == null) ? () -> List.<AgentMessage>of() : steeringMessages;
        followUpMessages = (followUpMessages == null) ? () -> List.<AgentMessage>of() : followUpMessages;
        eventSink = (eventSink == null) ? AgentEventSink.noop() : eventSink;
        llmEventSink = (llmEventSink == null) ? LlmEventSink.noop() : llmEventSink;
    }
}
