package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.message.ContextTransformer;
import site.pplee.jcode.agentcore.message.MessageProjector;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.agentcore.tool.AfterToolCall;
import site.pplee.jcode.agentcore.tool.BeforeToolCall;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;
import site.pplee.jcode.agentcore.turn.PrepareNextTurn;
import site.pplee.jcode.agentcore.turn.ShouldStopAfterTurn;

import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Configuration for constructing an {@link Agent}. Carries the initial
 * {@link AgentContext}, model and client bindings, the queue modes that
 * control how steering and follow-up messages are drained, and the
 * next-turn control hooks. No builder; the compact constructor validates
 * required fields and applies defaults to optional ones.
 *
 * <p>Defaults: {@code toolExecution = PARALLEL}, {@code eventSink = noop()},
 * {@code contextTransformer = identity()},
 * {@code messageProjector = standard()},
 * {@code steeringMode = ONE_AT_A_TIME},
 * {@code followUpMode = ONE_AT_A_TIME},
 * {@code thinkingLevel = PROVIDER_DEFAULT},
 * {@code prepareNextTurn = noop()},
 * {@code shouldStopAfterTurn = never()}.
 */
public record AgentConfig(
        AgentContext initialContext,
        ModelRef model,
        ModelClient modelClient,
        ObjectMapper objectMapper,
        ContextTransformer contextTransformer,
        MessageProjector messageProjector,
        ToolExecutionMode toolExecution,
        BeforeToolCall beforeToolCall,
        AfterToolCall afterToolCall,
        AgentEventSink eventSink,
        QueueMode steeringMode,
        QueueMode followUpMode,
        ThinkingLevel thinkingLevel,
        PrepareNextTurn prepareNextTurn,
        ShouldStopAfterTurn shouldStopAfterTurn
) {
    public AgentConfig {
        Objects.requireNonNull(initialContext, "initialContext must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(modelClient, "modelClient must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        contextTransformer = (contextTransformer == null) ? ContextTransformer.identity() : contextTransformer;
        messageProjector = (messageProjector == null) ? MessageProjector.standard() : messageProjector;
        toolExecution = (toolExecution == null) ? ToolExecutionMode.PARALLEL : toolExecution;
        beforeToolCall = (beforeToolCall == null) ? BeforeToolCall.noop() : beforeToolCall;
        afterToolCall = (afterToolCall == null) ? AfterToolCall.noop() : afterToolCall;
        eventSink = (eventSink == null) ? AgentEventSink.noop() : eventSink;
        steeringMode = (steeringMode == null) ? QueueMode.ONE_AT_A_TIME : steeringMode;
        followUpMode = (followUpMode == null) ? QueueMode.ONE_AT_A_TIME : followUpMode;
        thinkingLevel = (thinkingLevel == null) ? ThinkingLevel.PROVIDER_DEFAULT : thinkingLevel;
        prepareNextTurn = (prepareNextTurn == null) ? PrepareNextTurn.noop() : prepareNextTurn;
        shouldStopAfterTurn = (shouldStopAfterTurn == null) ? ShouldStopAfterTurn.never() : shouldStopAfterTurn;
    }

    /**
     * Legacy constructor without next-turn controls. Keeps the previous
     * constructor descriptor available so existing call sites compile
     * unchanged; the record's canonical form remains the extended one.
     */
    public AgentConfig(
            AgentContext initialContext,
            ModelRef model,
            ModelClient modelClient,
            ObjectMapper objectMapper,
            ContextTransformer contextTransformer,
            MessageProjector messageProjector,
            ToolExecutionMode toolExecution,
            BeforeToolCall beforeToolCall,
            AfterToolCall afterToolCall,
            AgentEventSink eventSink,
            QueueMode steeringMode,
            QueueMode followUpMode
    ) {
        this(initialContext, model, modelClient, objectMapper, contextTransformer,
                messageProjector, toolExecution, beforeToolCall, afterToolCall,
                eventSink, steeringMode, followUpMode,
                ThinkingLevel.PROVIDER_DEFAULT, PrepareNextTurn.noop(), ShouldStopAfterTurn.never());
    }
}
