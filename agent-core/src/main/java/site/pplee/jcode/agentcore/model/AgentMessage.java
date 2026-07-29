package site.pplee.jcode.agentcore.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Sealed message type carried in {@link AgentContext}. Messages are appended
 * in call order: a user prompt, the model's assistant response, and the
 * tool results produced from that response.
 */
public sealed interface AgentMessage
        permits AgentMessage.User, AgentMessage.Assistant, AgentMessage.ToolResult {

    /** A user-authored message (prompt or injected steering/follow-up). */
    record User(
            List<Content> content,
            Instant timestamp
    ) implements AgentMessage {
        public User {
            content = List.copyOf(Objects.requireNonNull(content, "content must not be null"));
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /** A model response; may carry text, thinking, and/or tool calls. */
    record Assistant(
            List<Content> content,
            StopReason stopReason,
            String errorMessage,
            Instant timestamp
    ) implements AgentMessage {
        public Assistant {
            content = List.copyOf(Objects.requireNonNull(content, "content must not be null"));
            Objects.requireNonNull(stopReason, "stopReason must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");

            if (errorMessage != null && !stopReason.isTerminalFailure()) {
                throw new IllegalArgumentException(
                        "errorMessage is only allowed for ERROR or ABORTED stop reasons");
            }
        }
    }

    /** Outcome of a single tool execution, written back for the model to read. */
    record ToolResult(
            String toolCallId,
            String toolName,
            List<Content> content,
            boolean error,
            boolean terminate,
            Instant timestamp
    ) implements AgentMessage {
        public ToolResult {
            Objects.requireNonNull(toolCallId, "toolCallId must not be null");
            Objects.requireNonNull(toolName, "toolName must not be null");
            content = List.copyOf(Objects.requireNonNull(content, "content must not be null"));
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }
}
