package site.pplee.jcode.ai.message;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * Sealed content model for a single message part. A message is an ordered
 * list of these; the {@code ai} layer never parses tool-call arguments.
 *
 * <p>Standard LLM content shared by the model-calling protocol:
 * a {@code TextContent | ThinkingContent | ToolCall} union.
 */
public sealed interface Content
        permits Content.Text, Content.Thinking, Content.ToolCall {

    /**
     * Plain text. {@code replayState} is opaque same-model provider state;
     * {@code null} means there is nothing to replay.
     */
    record Text(String text, ModelReplayState replayState) implements Content {
        public Text {
            Objects.requireNonNull(text, "text must not be null");
        }

        /** Compatibility constructor: text with no replay state. */
        public Text(String text) {
            this(text, null);
        }
    }

    /**
     * Model reasoning / thinking trace. {@code replayState} is opaque
     * same-model provider state; {@code null} means there is nothing to replay.
     */
    record Thinking(String text, ModelReplayState replayState) implements Content {
        public Thinking {
            Objects.requireNonNull(text, "text must not be null");
        }

        /** Compatibility constructor: thinking text with no replay state. */
        public Thinking(String text) {
            this(text, null);
        }
    }

    /** A tool invocation requested by the model; arguments are an opaque {@link JsonNode}. */
    record ToolCall(
            String id,
            String name,
            JsonNode arguments
    ) implements Content {
        public ToolCall {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(arguments, "arguments must not be null");

            if (id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }

            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }
    }
}
