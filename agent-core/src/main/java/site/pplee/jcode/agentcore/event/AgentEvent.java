package site.pplee.jcode.agentcore.event;

import site.pplee.jcode.agentcore.LoopResult;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;

import java.util.List;
import java.util.Objects;

/**
 * Sealed lifecycle event emitted through {@link AgentEventSink}. The loop
 * waits for each emit to complete, so observers see a deterministic order.
 * Model deltas are not part of these events (Wave 1 adds streaming via
 * {@code AssistantMessageStream}).
 *
 * <p>{@link MessageCompleted} carries the open {@link AgentMessage} (standard
 * messages arrive as {@link StandardAgentMessage} wrapping an {@code ai.Message}).
 * {@link ToolStarted} carries the standard {@link Content.ToolCall}.
 * {@link TurnCompleted} carries the standard {@link Message.Assistant} and the
 * {@link Message.ToolResultMessage} list written back to the model — these are
 * the standard {@code ai} transcript types, not the open {@code AgentMessage}.
 */
public sealed interface AgentEvent
        permits AgentEvent.AgentStarted, AgentEvent.TurnStarted,
                AgentEvent.MessageCompleted, AgentEvent.ToolStarted,
                AgentEvent.ToolUpdate, AgentEvent.ToolCompleted,
                AgentEvent.TurnCompleted, AgentEvent.AgentCompleted {

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

    /** Real-time progress update from a running tool. */
    record ToolUpdate(Content.ToolCall call, Content update) implements AgentEvent {
        public ToolUpdate {
            Objects.requireNonNull(call, "call must not be null");
            Objects.requireNonNull(update, "update must not be null");
        }
    }

    /** A tool finished and its result was appended. */
    record ToolCompleted(Message.ToolResultMessage result) implements AgentEvent {
        public ToolCompleted {
            Objects.requireNonNull(result, "result must not be null");
        }
    }

    /** A model turn finished, with its tool results (empty if none). */
    record TurnCompleted(
            Message.Assistant assistant,
            List<Message.ToolResultMessage> toolResults
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
