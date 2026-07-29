package site.pplee.jcode.agentcore.model;

import site.pplee.jcode.agentcore.spi.AgentTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable agent state: system prompt, ordered message history, and
 * registered tools. {@link #append} and {@link #appendAll} return a new
 * context rather than mutating this one.
 */
public record AgentContext(
        String systemPrompt,
        List<AgentMessage> messages,
        List<AgentTool<?>> tools
) {
    public AgentContext {
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
    }

    /** Return a new context with one message appended. */
    public AgentContext append(AgentMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        var newMessages = new ArrayList<AgentMessage>(this.messages);
        newMessages.add(message);
        return new AgentContext(systemPrompt, List.copyOf(newMessages), tools);
    }

    /** Return a new context with all messages appended; an empty list returns this. */
    public AgentContext appendAll(List<? extends AgentMessage> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.isEmpty()) {
            return this;
        }
        var newMessages = new ArrayList<AgentMessage>(this.messages);
        newMessages.addAll(messages);
        return new AgentContext(systemPrompt, List.copyOf(newMessages), tools);
    }
}
