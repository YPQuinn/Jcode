package site.pplee.jcode.agentcore.event;

import site.pplee.jcode.agentcore.model.AgentMessage;
import site.pplee.jcode.agentcore.model.Content;
import site.pplee.jcode.agentcore.model.LoopResult;

import java.util.List;
import java.util.Objects;

/**
 * Sealed lifecycle event emitted through {@link AgentEventSink}. The loop
 * waits for each emit to complete, so observers see a deterministic order.
 * Model deltas are not part of these events (see {@link LlmEventSink}).
 */
public sealed interface AgentEvent
        permits AgentEvent.AgentStarted, AgentEvent.TurnStarted,
                AgentEvent.MessageCompleted, AgentEvent.ToolStarted,
                AgentEvent.ToolCompleted, AgentEvent.TurnCompleted,
                AgentEvent.AgentCompleted {

    /** A run has begun. */
    record AgentStarted() implements AgentEvent {}

    /** A new model turn has begun. */
    record TurnStarted() implements AgentEvent {}

    /** A message was appended to the context. */
    record MessageCompleted(AgentMessage message) implements AgentEvent {
        public MessageCompleted {
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    /** A tool is about to execute. */
    record ToolStarted(Content.ToolCall call) implements AgentEvent {
        public ToolStarted {
            Objects.requireNonNull(call, "call must not be null");
        }
    }

    /** A tool finished and its result was appended. */
    record ToolCompleted(AgentMessage.ToolResult result) implements AgentEvent {
        public ToolCompleted {
            Objects.requireNonNull(result, "result must not be null");
        }
    }

    /** A model turn finished, with its tool results (empty if none). */
    record TurnCompleted(
            AgentMessage.Assistant assistant,
            List<AgentMessage.ToolResult> toolResults
    ) implements AgentEvent {
        public TurnCompleted {
            Objects.requireNonNull(assistant, "assistant must not be null");
            toolResults = List.copyOf(Objects.requireNonNull(toolResults, "toolResults must not be null"));
        }
    }

    /** The run finished; {@link LoopResult} is final. */
    record AgentCompleted(LoopResult result) implements AgentEvent {
        public AgentCompleted {
            Objects.requireNonNull(result, "result must not be null");
        }
    }
}
