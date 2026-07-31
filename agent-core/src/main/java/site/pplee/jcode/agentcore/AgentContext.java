package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.tool.AgentTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable agent state: system prompt, ordered message history, and
 * registered tools. {@link #append} and {@link #appendAll} return a new
 * context rather than mutating this one.
 *
 * <p>Holds the open {@link AgentMessage} transcript type (standard messages
 * wrapped in {@link site.pplee.jcode.agentcore.message.StandardAgentMessage},
 * plus future custom product messages) and executable {@link AgentTool}s.
 * The model-call seam projects a request-local view of these to
 * {@code ai.Message}/{@code ai.ToolSpec} via
 * {@link site.pplee.jcode.agentcore.message.ContextTransformer} and
 * {@link site.pplee.jcode.agentcore.message.MessageProjector}; the transcript
 * itself is never modified by projection.
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
