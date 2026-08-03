package site.pplee.jcode.agentcore;

import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.agentcore.message.ContextTransformer;
import site.pplee.jcode.agentcore.message.MessageProjector;
import site.pplee.jcode.agentcore.queue.PendingMessageSource;
import site.pplee.jcode.agentcore.tool.AfterToolCall;
import site.pplee.jcode.agentcore.tool.BeforeToolCall;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;
import site.pplee.jcode.agentcore.turn.PrepareNextTurn;
import site.pplee.jcode.agentcore.turn.ShouldStopAfterTurn;
import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;

import java.util.List;
import java.util.Objects;

/**
 * Per-run configuration assembled by {@link Agent}: model/client bindings,
 * shared {@link ObjectMapper}, tool execution mode, steering/follow-up
 * sources, the {@link RunEventEmitter} event delivery adapter, and the
 * next-turn control hooks. Optional sources/emitter default to empty/noop.
 *
 * <p>Carries only {@code ai} model-identity types ({@link ModelRef}) and the
 * {@link ModelClient} seam — no provider SDK. Streaming deltas flow through
 * {@link site.pplee.jcode.ai.stream.AssistantMessageStream}.
 *
 * <p>Context projection (Wave 3): {@link ContextTransformer} runs first
 * (async, cancellation-aware), then {@link MessageProjector} (sync), before
 * every {@code ModelRequest}. Both default to identity/standard when null.
 *
 * <p>Next-turn control (Wave 4): {@code thinkingLevel} is the run's initial
 * thinking preference, {@code prepareNextTurn} and {@code shouldStopAfterTurn}
 * are invoked between turns after {@code TurnCompleted}. The config stays
 * immutable; per-turn updates live in {@link LoopState}.
 */
record AgentLoopConfig(
        ModelRef model,
        ModelClient modelClient,
        ObjectMapper objectMapper,
        ContextTransformer contextTransformer,
        MessageProjector messageProjector,
        ToolExecutionMode toolExecution,
        BeforeToolCall beforeToolCall,
        AfterToolCall afterToolCall,
        PendingMessageSource steeringMessages,
        PendingMessageSource followUpMessages,
        RunEventEmitter events,
        ThinkingLevel thinkingLevel,
        PrepareNextTurn prepareNextTurn,
        ShouldStopAfterTurn shouldStopAfterTurn
) {
    AgentLoopConfig {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(modelClient, "modelClient must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        contextTransformer = (contextTransformer == null) ? ContextTransformer.identity() : contextTransformer;
        messageProjector = (messageProjector == null) ? MessageProjector.standard() : messageProjector;
        toolExecution = (toolExecution == null) ? ToolExecutionMode.PARALLEL : toolExecution;
        beforeToolCall = (beforeToolCall == null) ? BeforeToolCall.noop() : beforeToolCall;
        afterToolCall = (afterToolCall == null) ? AfterToolCall.noop() : afterToolCall;
        steeringMessages = (steeringMessages == null) ? List::of : steeringMessages;
        followUpMessages = (followUpMessages == null) ? List::of : followUpMessages;
        events = (events == null) ? RunEventEmitter.noop() : events;
        thinkingLevel = (thinkingLevel == null) ? ThinkingLevel.PROVIDER_DEFAULT : thinkingLevel;
        prepareNextTurn = (prepareNextTurn == null) ? PrepareNextTurn.noop() : prepareNextTurn;
        shouldStopAfterTurn = (shouldStopAfterTurn == null) ? ShouldStopAfterTurn.never() : shouldStopAfterTurn;
    }

    /**
     * Legacy constructor without next-turn controls. Keeps the previous
     * constructor descriptor available so existing low-level call sites
     * compile unchanged; the record's canonical form remains the extended one.
     */
    AgentLoopConfig(
            ModelRef model,
            ModelClient modelClient,
            ObjectMapper objectMapper,
            ContextTransformer contextTransformer,
            MessageProjector messageProjector,
            ToolExecutionMode toolExecution,
            BeforeToolCall beforeToolCall,
            AfterToolCall afterToolCall,
            PendingMessageSource steeringMessages,
            PendingMessageSource followUpMessages,
            RunEventEmitter events
    ) {
        this(model, modelClient, objectMapper, contextTransformer, messageProjector,
                toolExecution, beforeToolCall, afterToolCall, steeringMessages, followUpMessages,
                events, ThinkingLevel.PROVIDER_DEFAULT, PrepareNextTurn.noop(), ShouldStopAfterTurn.never());
    }
}
