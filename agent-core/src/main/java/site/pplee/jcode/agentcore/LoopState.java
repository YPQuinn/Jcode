package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.model.AgentContext;
import site.pplee.jcode.agentcore.model.AgentMessage;
import site.pplee.jcode.agentcore.model.LoopResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Core-layer mutable run state. The only type in the core layer allowed to
 * hold a mutable message collection. {@link AgentContext} stays immutable;
 * each {@code append} replaces the {@code context} reference with a new
 * immutable context while {@code newMessages} grows in place.
 */
final class LoopState {
    private AgentContext context;
    private final List<AgentMessage> newMessages = new ArrayList<>();

    LoopState(AgentContext initialContext) {
        this.context = Objects.requireNonNull(initialContext, "initialContext must not be null");
    }

    AgentContext context() {
        return context;
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

    LoopResult result() {
        return new LoopResult(context, List.copyOf(newMessages));
    }
}
