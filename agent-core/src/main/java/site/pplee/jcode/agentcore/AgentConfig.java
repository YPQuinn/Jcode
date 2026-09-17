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
import site.pplee.jcode.ai.client.ModelRequestOptions;
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
 * {@code shouldStopAfterTurn = never()},
 * {@code modelRequestOptions = defaults()}.
 *
 * <p>{@link ModelRequestOptions} are fixed for the Agent lifetime and passed
 * through unchanged on every model call. This batch does not let
 * {@link PrepareNextTurn} replace them.
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
        ShouldStopAfterTurn shouldStopAfterTurn,
        ModelRequestOptions modelRequestOptions
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
        modelRequestOptions = (modelRequestOptions == null) ? ModelRequestOptions.defaults() : modelRequestOptions;
    }

    /**
     * Compatibility constructor without request options. Uses
     * {@link ModelRequestOptions#defaults()}.
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
            QueueMode followUpMode,
            ThinkingLevel thinkingLevel,
            PrepareNextTurn prepareNextTurn,
            ShouldStopAfterTurn shouldStopAfterTurn
    ) {
        this(initialContext, model, modelClient, objectMapper, contextTransformer,
                messageProjector, toolExecution, beforeToolCall, afterToolCall,
                eventSink, steeringMode, followUpMode, thinkingLevel, prepareNextTurn,
                shouldStopAfterTurn, ModelRequestOptions.defaults());
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
                ThinkingLevel.PROVIDER_DEFAULT, PrepareNextTurn.noop(), ShouldStopAfterTurn.never(),
                ModelRequestOptions.defaults());
    }
}
