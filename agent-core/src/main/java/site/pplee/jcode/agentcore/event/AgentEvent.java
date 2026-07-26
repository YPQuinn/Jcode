package site.pplee.jcode.agentcore.event;

import site.pplee.jcode.agentcore.model.AgentMessage;
import site.pplee.jcode.agentcore.model.Content;
import site.pplee.jcode.agentcore.model.LoopResult;

import java.util.List;
import java.util.Objects;

public sealed interface AgentEvent
        permits AgentEvent.AgentStarted, AgentEvent.TurnStarted,
                AgentEvent.MessageCompleted, AgentEvent.ToolStarted,
                AgentEvent.ToolCompleted, AgentEvent.TurnCompleted,
                AgentEvent.AgentCompleted {

    record AgentStarted() implements AgentEvent {}

    record TurnStarted() implements AgentEvent {}

    record MessageCompleted(AgentMessage message) implements AgentEvent {
        public MessageCompleted {
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    record ToolStarted(Content.ToolCall call) implements AgentEvent {
        public ToolStarted {
            Objects.requireNonNull(call, "call must not be null");
        }
    }

    record ToolCompleted(AgentMessage.ToolResult result) implements AgentEvent {
        public ToolCompleted {
            Objects.requireNonNull(result, "result must not be null");
        }
    }

    record TurnCompleted(
            AgentMessage.Assistant assistant,
            List<AgentMessage.ToolResult> toolResults
    ) implements AgentEvent {
        public TurnCompleted {
            Objects.requireNonNull(assistant, "assistant must not be null");
            toolResults = List.copyOf(Objects.requireNonNull(toolResults, "toolResults must not be null"));
        }
    }

    record AgentCompleted(LoopResult result) implements AgentEvent {
        public AgentCompleted {
            Objects.requireNonNull(result, "result must not be null");
        }
    }
}
