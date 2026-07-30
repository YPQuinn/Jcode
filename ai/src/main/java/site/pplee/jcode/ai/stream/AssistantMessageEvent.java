package site.pplee.jcode.ai.stream;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;

import java.util.Objects;

/**
 * Sealed event protocol for {@link AssistantMessageStream}. Streams emit
 * {@link Start} before partial updates, then terminate with either
 * {@link Done} (carrying the final successful {@link Message.Assistant}) or
 * {@link Error} (carrying the final failure/aborted assistant message).
 *
 * <p>Delta events carry a {@code contentIndex} that associates each event
 * with its position in {@link Message.Assistant#content()}. Events for
 * different content blocks may interleave; consumers must use
 * {@code contentIndex} to associate each delta/end event with its block and
 * must not assume a block's {@code *_start}/{@code *_delta}/{@code *_end}
 * sequence is uninterrupted by events for other blocks.
 *
 * <p>Every delta event also carries the current {@code partial} — the
 * accumulated {@link Message.Assistant} up to that point. The partial's
 * {@link Message.Assistant#stopReason()} is a placeholder
 * ({@link StopReason#STOP}) until the terminal event arrives.
 */
public sealed interface AssistantMessageEvent
        permits AssistantMessageEvent.Start,
                AssistantMessageEvent.TextStart,
                AssistantMessageEvent.TextDelta,
                AssistantMessageEvent.TextEnd,
                AssistantMessageEvent.ThinkingStart,
                AssistantMessageEvent.ThinkingDelta,
                AssistantMessageEvent.ThinkingEnd,
                AssistantMessageEvent.ToolCallStart,
                AssistantMessageEvent.ToolCallDelta,
                AssistantMessageEvent.ToolCallEnd,
                AssistantMessageEvent.Done,
                AssistantMessageEvent.Error {

    /** The current accumulated partial assistant message. */
    Message.Assistant partial();

    /** Stream started; carries the initial partial assistant message. */
    record Start(Message.Assistant partial) implements AssistantMessageEvent {
        public Start {
            Objects.requireNonNull(partial, "partial must not be null");
        }
    }

    /** A text content block started at the given index. */
    record TextStart(int contentIndex, Message.Assistant partial) implements AssistantMessageEvent {
        public TextStart {
            Objects.requireNonNull(partial, "partial must not be null");
        }
    }

    /** A text delta arrived for the content block at the given index. */
    record TextDelta(int contentIndex, String delta, Message.Assistant partial) implements AssistantMessageEvent {
        public TextDelta {
            Objects.requireNonNull(delta, "delta must not be null");
            Objects.requireNonNull(partial, "partial must not be null");
        }
    }

    /** The text content block at the given index is complete. */
    record TextEnd(int contentIndex, String content, Message.Assistant partial) implements AssistantMessageEvent {
        public TextEnd {
            Objects.requireNonNull(content, "content must not be null");
            Objects.requireNonNull(partial, "partial must not be null");
        }
    }

    /** A thinking content block started at the given index. */
    record ThinkingStart(int contentIndex, Message.Assistant partial) implements AssistantMessageEvent {
        public ThinkingStart {
            Objects.requireNonNull(partial, "partial must not be null");
        }
    }

    /** A thinking delta arrived for the content block at the given index. */
    record ThinkingDelta(int contentIndex, String delta, Message.Assistant partial) implements AssistantMessageEvent {
        public ThinkingDelta {
            Objects.requireNonNull(delta, "delta must not be null");
            Objects.requireNonNull(partial, "partial must not be null");
        }
    }

    /** The thinking content block at the given index is complete. */
    record ThinkingEnd(int contentIndex, String content, Message.Assistant partial) implements AssistantMessageEvent {
        public ThinkingEnd {
            Objects.requireNonNull(content, "content must not be null");
            Objects.requireNonNull(partial, "partial must not be null");
        }
    }

    /** A tool-call content block started at the given index. */
    record ToolCallStart(int contentIndex, Message.Assistant partial) implements AssistantMessageEvent {
        public ToolCallStart {
            Objects.requireNonNull(partial, "partial must not be null");
        }
    }

    /** A tool-call argument delta arrived for the content block at the given index. */
    record ToolCallDelta(int contentIndex, String delta, Message.Assistant partial) implements AssistantMessageEvent {
        public ToolCallDelta {
            Objects.requireNonNull(delta, "delta must not be null");
            Objects.requireNonNull(partial, "partial must not be null");
        }
    }

    /** The tool-call content block at the given index is complete. */
    record ToolCallEnd(int contentIndex, Content.ToolCall toolCall, Message.Assistant partial) implements AssistantMessageEvent {
        public ToolCallEnd {
            Objects.requireNonNull(toolCall, "toolCall must not be null");
            Objects.requireNonNull(partial, "partial must not be null");
        }
    }

    /**
     * Stream completed successfully. {@code reason} is
     * {@link StopReason#STOP}, {@link StopReason#TOOL_CALL}, or
     * {@link StopReason#LENGTH}. The {@code message} is the final
     * assistant message.
     */
    record Done(StopReason reason, Message.Assistant message) implements AssistantMessageEvent {
        public Done {
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(message, "message must not be null");
            if (reason.isTerminalFailure()) {
                throw new IllegalArgumentException(
                        "Done reason must not be a terminal failure; use Error instead");
            }
        }

        @Override
        public Message.Assistant partial() { return message; }
    }

    /**
     * Stream terminated by a model error or cancellation. {@code reason} is
     * {@link StopReason#ERROR} or {@link StopReason#ABORTED}. The {@code error}
     * is the final assistant message carrying any partial content and the
     * {@code errorMessage}.
     */
    record Error(StopReason reason, Message.Assistant error) implements AssistantMessageEvent {
        public Error {
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(error, "error must not be null");
            if (!reason.isTerminalFailure()) {
                throw new IllegalArgumentException(
                        "Error reason must be a terminal failure (ERROR or ABORTED)");
            }
        }

        @Override
        public Message.Assistant partial() { return error; }
    }
}
