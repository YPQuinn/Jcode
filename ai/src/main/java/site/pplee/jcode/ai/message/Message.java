package site.pplee.jcode.ai.message;

import site.pplee.jcode.ai.model.ModelRef;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Sealed standard LLM message type: a user prompt, a model response, or a tool
 * result written back for the model to read. This is the <em>only</em> set of
 * standard LLM message types in the repo.
 *
 * <p>{@code agent-core} keeps an open {@code AgentMessage} interface so product
 * layers can add custom transcript messages; at the model-call seam those are
 * projected back to this type before constructing a {@code ModelRequest}.
 */
public sealed interface Message
        permits Message.User, Message.Assistant, Message.ToolResultMessage {

    /**
     * A user-authored message (prompt or injected steering/follow-up).
     * Content may include {@link Content.Text} and {@link Content.Image}.
     */
    record User(
            List<Content> content,
            Instant timestamp
    ) implements Message {
        public User {
            content = List.copyOf(Objects.requireNonNull(content, "content must not be null"));
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * A model response; may carry text, thinking, and/or tool calls.
     * {@code errorMessage} is only allowed when {@link StopReason#isTerminalFailure()}.
     * {@code usage} carries token-usage metadata reported by the provider;
     * use {@link Usage#zero()} when no usage is reported.
     * {@code sourceModel} is the provider-neutral model that produced this
     * message; {@code null} means synthetic, legacy, or unknown origin.
     * {@code metadata} is bounded correlation data; OpenAI output message
     * id/phase stay in {@link ModelReplayState}, not here.
     */
    record Assistant(
            List<Content> content,
            StopReason stopReason,
            String errorMessage,
            Usage usage,
            Instant timestamp,
            ModelRef sourceModel,
            ResponseMetadata metadata
    ) implements Message {
        public Assistant {
            content = List.copyOf(Objects.requireNonNull(content, "content must not be null"));
            Objects.requireNonNull(stopReason, "stopReason must not be null");
            Objects.requireNonNull(usage, "usage must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            Objects.requireNonNull(metadata, "metadata must not be null");

            if (errorMessage != null && !stopReason.isTerminalFailure()) {
                throw new IllegalArgumentException(
                        "errorMessage is only allowed for ERROR or ABORTED stop reasons");
            }
        }

        /**
         * Compatibility constructor: assistant with a source model and empty
         * metadata.
         */
        public Assistant(
                List<Content> content,
                StopReason stopReason,
                String errorMessage,
                Usage usage,
                Instant timestamp,
                ModelRef sourceModel
        ) {
            this(content, stopReason, errorMessage, usage, timestamp, sourceModel, ResponseMetadata.empty());
        }

        /** Compatibility constructor: assistant with unknown source model and empty metadata. */
        public Assistant(
                List<Content> content,
                StopReason stopReason,
                String errorMessage,
                Usage usage,
                Instant timestamp
        ) {
            this(content, stopReason, errorMessage, usage, timestamp, null, ResponseMetadata.empty());
        }

        /** Convenience: an assistant result terminated by {@code stopReason} with no error and zero usage. */
        public static Assistant of(List<Content> content, StopReason stopReason, Instant timestamp) {
            return new Assistant(content, stopReason, null, Usage.zero(), timestamp, null, ResponseMetadata.empty());
        }
    }

    /**
     * Outcome of a single tool execution, written back for the model to read.
     * Content may include {@link Content.Text} and {@link Content.Image}.
     * {@code error} marks a failure the model can react to. The runtime-only
     * {@code terminate} flag (requesting chain stop) is not part of the
     * standard LLM transcript; it lives on the agent-runtime execution result.
     */
    record ToolResultMessage(
            String toolCallId,
            String toolName,
            List<Content> content,
            boolean error,
            Instant timestamp
    ) implements Message {
        public ToolResultMessage {
            Objects.requireNonNull(toolCallId, "toolCallId must not be null");
            Objects.requireNonNull(toolName, "toolName must not be null");
            content = List.copyOf(Objects.requireNonNull(content, "content must not be null"));
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }
}
