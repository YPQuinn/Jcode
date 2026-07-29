package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.queue.PendingMessageSource;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;

import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.model.ModelRef;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

/**
 * Per-run configuration assembled by {@link Agent}: model/client bindings,
 * shared {@link ObjectMapper}, tool execution mode, steering/follow-up
 * sources, and the event sink. Optional sources/sink default to empty/noop.
 *
 * <p>Carries only {@code ai} model-identity types ({@link ModelRef}) and the
 * {@link ModelClient} seam — no provider SDK; streaming is reintroduced in
 * Wave 1 via {@code AssistantMessageStream}.
 */
record AgentLoopConfig(
        ModelRef model,
        ModelClient modelClient,
        ObjectMapper objectMapper,
        ToolExecutionMode toolExecution,
        PendingMessageSource steeringMessages,
        PendingMessageSource followUpMessages,
        AgentEventSink eventSink
) {
    AgentLoopConfig {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(modelClient, "modelClient must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        toolExecution = (toolExecution == null) ? ToolExecutionMode.PARALLEL : toolExecution;
        steeringMessages = (steeringMessages == null) ? () -> List.<AgentMessage>of() : steeringMessages;
        followUpMessages = (followUpMessages == null) ? () -> List.<AgentMessage>of() : followUpMessages;
        eventSink = (eventSink == null) ? AgentEventSink.noop() : eventSink;
    }
}
