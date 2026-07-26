package site.pplee.jcode.agentcore.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public sealed interface AgentMessage
        permits AgentMessage.User, AgentMessage.Assistant, AgentMessage.ToolResult {

    record User(
            List<Content> content,
            Instant timestamp
    ) implements AgentMessage {
        public User {
            content = List.copyOf(Objects.requireNonNull(content, "content must not be null"));
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

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
