package site.pplee.jcode.agentcore.queue;

import site.pplee.jcode.agentcore.message.AgentMessage;

import java.util.Objects;

/** A claimed message with an optional opaque identity supplied by its source. */
public record PendingMessage(AgentMessage message, String inputId) {
    public PendingMessage {
        Objects.requireNonNull(message, "message must not be null");
    }

    public PendingMessage(AgentMessage message) {
        this(message, null);
    }
}
