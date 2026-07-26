package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.model.AgentContext;
import site.pplee.jcode.agentcore.model.ModelRef;
import site.pplee.jcode.agentcore.model.ToolExecutionMode;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.agentcore.spi.LlmClient;
import site.pplee.jcode.agentcore.spi.LlmEventSink;

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
 * {@code llmEventSink = noop()}, {@code steeringMode = ONE_AT_A_TIME},
 * {@code followUpMode = ONE_AT_A_TIME} (matches pi agent.ts:224-225).
 */
public record AgentConfig(
        AgentContext initialContext,
        ModelRef model,
        LlmClient llmClient,
        ObjectMapper objectMapper,
        ToolExecutionMode toolExecution,
        AgentEventSink eventSink,
        LlmEventSink llmEventSink,
        QueueMode steeringMode,
        QueueMode followUpMode
) {
    public AgentConfig {
        Objects.requireNonNull(initialContext, "initialContext must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(llmClient, "llmClient must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        toolExecution = (toolExecution == null) ? ToolExecutionMode.PARALLEL : toolExecution;
        eventSink = (eventSink == null) ? AgentEventSink.noop() : eventSink;
        llmEventSink = (llmEventSink == null) ? LlmEventSink.noop() : llmEventSink;
        steeringMode = (steeringMode == null) ? QueueMode.ONE_AT_A_TIME : steeringMode;
        followUpMode = (followUpMode == null) ? QueueMode.ONE_AT_A_TIME : followUpMode;
    }
}
