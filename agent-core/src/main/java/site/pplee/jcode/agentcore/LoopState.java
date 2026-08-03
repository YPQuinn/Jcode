package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.turn.NextTurnUpdate;

import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Core-layer mutable run state. The only type in the core layer allowed to
 * hold a mutable message collection. {@link AgentContext} stays immutable;
 * each {@code append} replaces the {@code context} reference with a new
 * immutable context while {@code newMessages} grows in place.
 *
 * <p>Also owns the run-local model and thinking level. A validated
 * {@link NextTurnUpdate} is applied atomically here, so the next model
 * request reads the replaced context, model, and thinking level from this
 * state instead of from the immutable run config.
 */
final class LoopState {
    private AgentContext context;
    private ModelRef model;
    private ThinkingLevel thinkingLevel;
    private final List<AgentMessage> newMessages = new ArrayList<>();

    LoopState(AgentContext initialContext, ModelRef model, ThinkingLevel thinkingLevel) {
        this.context = Objects.requireNonNull(initialContext, "initialContext must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.thinkingLevel = Objects.requireNonNull(thinkingLevel, "thinkingLevel must not be null");
    }

    AgentContext context() {
        return context;
    }

    ModelRef model() {
        return model;
    }

    ThinkingLevel thinkingLevel() {
        return thinkingLevel;
    }

    /** Immutable snapshot of the messages this run has appended so far. */
    List<AgentMessage> newMessages() {
        return List.copyOf(newMessages);
    }

    void append(AgentMessage message) {
        context = context.append(Objects.requireNonNull(message, "message must not be null"));
        newMessages.add(message);
    }

    void appendAll(List<? extends AgentMessage> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.isEmpty()) {
            return;
        }
        context = context.appendAll(messages);
        newMessages.addAll(messages);
    }

    /**
     * Apply a fully validated next-turn update. Present components replace
     * the run-local context, model, or thinking level; empty components keep
     * the current values. Validation happens before this call, so the patch
     * takes effect atomically for the next turn.
     */
    void applyUpdate(NextTurnUpdate update) {
        Objects.requireNonNull(update, "update must not be null");
        update.context().ifPresent(ctx -> this.context = ctx);
        update.model().ifPresent(m -> this.model = m);
        update.thinkingLevel().ifPresent(l -> this.thinkingLevel = l);
    }

    LoopResult result() {
        return new LoopResult(context, List.copyOf(newMessages));
    }
}
