package site.pplee.jcode.agentcore.message;

import site.pplee.jcode.ai.message.Message;

import java.util.Objects;

/**
 * Standard bridge between the open {@link AgentMessage} transcript type and the
 * {@link Message standard LLM message} set in {@code ai}. Wraps an
 * {@code ai.Message} (a {@code User}, {@code Assistant}, or
 * {@code ToolResultMessage}) so it can live in an
 * {@code AgentContext} alongside future custom product messages.
 *
 * <p>The default message projection (inline in {@code AgentLoop} for Wave 0,
 * formalized as {@code MessageProjector} in Wave 3) unwraps
 * {@code StandardAgentMessage} back to {@code ai.Message}; unknown product
 * messages are filtered out.
 *
 * <p>Transcript shape:
 * <pre>
 *   agent-core.AgentMessage（open interface）
 *     ├── StandardAgentMessage(ai.Message)
 *     └── coding-agent 自己定义的消息类型
 * </pre>
 */
public final class StandardAgentMessage implements AgentMessage {
    private final Message message;

    public StandardAgentMessage(Message message) {
        this.message = Objects.requireNonNull(message, "message must not be null");
    }

    /** Convenience: wrap a standard {@link Message}. */
    public static StandardAgentMessage of(Message message) {
        return new StandardAgentMessage(message);
    }

    /** The wrapped standard {@link Message}. */
    public Message message() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StandardAgentMessage s && message.equals(s.message);
    }

    @Override
    public int hashCode() {
        return message.hashCode();
    }

    @Override
    public String toString() {
        return "StandardAgentMessage[" + message + "]";
    }
}
