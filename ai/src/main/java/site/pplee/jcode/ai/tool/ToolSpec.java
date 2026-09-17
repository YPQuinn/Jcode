package site.pplee.jcode.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Objects;

/**
 * Serializable tool declaration handed to a model: a stable name, a human
 * description, a JSON Schema describing the arguments, and an optional
 * {@link ToolInputConstraint}. The {@code ai} layer knows only the
 * <em>declarable</em> shape of a tool; execution and update capability live
 * in {@code agent-core.AgentTool}.
 */
public record ToolSpec(
        String name,
        String description,
        JsonNode parameters,
        ToolInputConstraint constraint
) {
    public ToolSpec {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
        Objects.requireNonNull(constraint, "constraint must not be null");

        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /**
     * Compatibility constructor: ordinary function tool with
     * {@link ToolInputConstraint#none()}.
     */
    public ToolSpec(String name, String description, JsonNode parameters) {
        this(name, description, parameters, ToolInputConstraint.none());
    }

    /** A minimal spec with an empty description, a null-schema, and no constraint. */
    public static ToolSpec minimal(String name) {
        return new ToolSpec(name, "", NullNode.getInstance());
    }
}
