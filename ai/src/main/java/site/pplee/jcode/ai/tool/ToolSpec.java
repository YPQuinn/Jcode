package site.pplee.jcode.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Objects;

/**
 * Serializable tool declaration handed to a model: a stable name, a human
 * description, and a JSON Schema describing the arguments. The {@code ai}
 * layer knows only the <em>declarable</em> shape of a tool; execution and
 * update capability live in {@code agent-core.AgentTool}.
 */
public record ToolSpec(
        String name,
        String description,
        JsonNode parameters
) {
    public ToolSpec {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");

        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /** A minimal spec with an empty description and a null-schema. */
    public static ToolSpec minimal(String name) {
        return new ToolSpec(name, "", NullNode.getInstance());
    }
}
