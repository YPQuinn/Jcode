package site.pplee.jcode.agentcore.event;

import site.pplee.jcode.agentcore.LoopResult;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;

import java.util.List;
import java.util.Objects;

/**
 * Sealed lifecycle event emitted through {@link AgentEventSink}. The loop
 * awaits each emit before proceeding, so lifecycle events are observed in a
 * deterministic order. With parallel tool execution, {@link ToolUpdate}
 * events are emitted by tool worker threads and may interleave with
 * lifecycle events emitted by the loop thread; while a tool call settles
 * normally the runtime guarantees its per-tool lifecycle
 * ({@link ToolStarted} → {@link ToolUpdate}… → {@link ToolCompleted}) and
 * completion-order {@link ToolCompleted} delivery, but not a global order
 * across concurrent tool updates. An infrastructure failure (for example an
 * event delivery failure) may abort a per-tool lifecycle after
 * {@link ToolStarted} without a {@link ToolCompleted}.
 * <p>Streaming deltas are delivered through {@link MessageUpdated} events,
 * which carry the low-level {@link AssistantMessageEvent} from the model
 * stream. The full sequence for an assistant message is
 * {@link MessageStarted} → {@link MessageUpdated}… → {@link MessageCompleted}.
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
                AgentEvent.MessageStarted, AgentEvent.MessageUpdated,
                AgentEvent.MessageCompleted,
                AgentEvent.ToolStarted, AgentEvent.ToolUpdate, AgentEvent.ToolCompleted,
                AgentEvent.TurnCompleted, AgentEvent.AgentCompleted {

    /** A run has begun. */
    record AgentStarted() implements AgentEvent {}

    /** A new model turn has begun. */
    record TurnStarted() implements AgentEvent {}

    /** A message has begun streaming into the transcript (user, assistant partial, or tool result). */
    record MessageStarted(AgentMessage message) implements AgentEvent {
        public MessageStarted {
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    /** A streaming delta for an assistant message; carries the low-level stream event. */
    record MessageUpdated(AgentMessage message, AssistantMessageEvent delta) implements AgentEvent {
        public MessageUpdated {
            Objects.requireNonNull(message, "message must not be null");
            Objects.requireNonNull(delta, "delta must not be null");
        }
    }

    /** A message was appended to the context; inputId is an optional source identity. */
    record MessageCompleted(AgentMessage message, String inputId) implements AgentEvent {
        public MessageCompleted {
            Objects.requireNonNull(message, "message must not be null");
        }

        public MessageCompleted(AgentMessage message) {
            this(message, null);
        }
    }

    /**
     * A tool call has entered the prepare phase. Emitted by the loop thread
     * in tool-call source order; the call may still fail preparation and
     * never execute.
     */
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

    /**
     * A tool finished. In a parallel batch, {@code ToolCompleted} events are
     * emitted by the loop thread in actual completion order; the transcript
     * tool-result message is appended later, in source order. For a single
     * tool call the lifecycle is {@link ToolStarted} → {@link ToolUpdate}… →
     * {@link ToolCompleted} while the call settles normally; an
     * infrastructure failure may end the lifecycle earlier.
     */
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
