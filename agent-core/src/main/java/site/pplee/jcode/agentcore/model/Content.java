package site.pplee.jcode.agentcore.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

public sealed interface Content
        permits Content.Text, Content.Thinking, Content.ToolCall {

    record Text(String text) implements Content {
        public Text {
            Objects.requireNonNull(text, "text must not be null");
        }
    }

    record Thinking(String text) implements Content {
        public Thinking {
            Objects.requireNonNull(text, "text must not be null");
        }
    }

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