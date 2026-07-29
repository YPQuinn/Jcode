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

    /** Plain text. */
    record Text(String text) implements Content {
        public Text {
            Objects.requireNonNull(text, "text must not be null");
        }
    }

    /** Model reasoning / thinking trace. */
    record Thinking(String text) implements Content {
        public Thinking {
            Objects.requireNonNull(text, "text must not be null");
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
