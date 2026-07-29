package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.agentcore.tool.AfterToolCall;
import site.pplee.jcode.agentcore.tool.BeforeToolCall;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;

import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.model.ModelRef;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Configuration for constructing an {@link Agent}. Carries the initial
 * {@link AgentContext}, model and client bindings, and the queue modes that
 * control how steering and follow-up messages are drained. No builder; the
 * compact constructor validates required fields and applies defaults to
 * optional ones.
 *
 * <p>Defaults: {@code toolExecution = PARALLEL}, {@code eventSink = noop()},
 * {@code steeringMode = ONE_AT_A_TIME},
 * {@code followUpMode = ONE_AT_A_TIME}.
 */
public record AgentConfig(
        AgentContext initialContext,
        ModelRef model,
        ModelClient modelClient,
        ObjectMapper objectMapper,
        ToolExecutionMode toolExecution,
        BeforeToolCall beforeToolCall,
        AfterToolCall afterToolCall,
        AgentEventSink eventSink,
        QueueMode steeringMode,
        QueueMode followUpMode
) {
    public AgentConfig {
        Objects.requireNonNull(initialContext, "initialContext must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(modelClient, "modelClient must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        toolExecution = (toolExecution == null) ? ToolExecutionMode.PARALLEL : toolExecution;
        beforeToolCall = (beforeToolCall == null) ? BeforeToolCall.noop() : beforeToolCall;
        afterToolCall = (afterToolCall == null) ? AfterToolCall.noop() : afterToolCall;
        eventSink = (eventSink == null) ? AgentEventSink.noop() : eventSink;
        steeringMode = (steeringMode == null) ? QueueMode.ONE_AT_A_TIME : steeringMode;
        followUpMode = (followUpMode == null) ? QueueMode.ONE_AT_A_TIME : followUpMode;
    }
}
