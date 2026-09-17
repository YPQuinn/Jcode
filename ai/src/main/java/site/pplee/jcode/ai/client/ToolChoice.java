package site.pplee.jcode.ai.client;

import java.util.Objects;

/**
 * Provider-neutral tool-selection policy for one model call. Modes and a
 * specific tool name are distinct types so callers cannot encode both in a
 * bare string.
 */
public sealed interface ToolChoice permits ToolChoice.Mode, ToolChoice.Specific {

    /** Named selection modes that do not target a single tool. */
    enum Mode implements ToolChoice {
        /** Let the model decide whether to call a tool. */
        AUTO,
        /** Forbid tool calls for this request. */
        NONE,
        /** Require the model to call at least one declared tool. */
        REQUIRED
    }

    /**
     * Force the model to call one declared tool. {@code toolName} must be
     * non-blank; existence in the request's tool list is checked by the
     * adapter.
     */
    record Specific(String toolName) implements ToolChoice {
        public Specific {
            Objects.requireNonNull(toolName, "toolName must not be null");
            if (toolName.isBlank()) {
                throw new IllegalArgumentException("toolName must not be blank");
            }
        }
    }

    static ToolChoice auto() {
        return Mode.AUTO;
    }

    static ToolChoice none() {
        return Mode.NONE;
    }

    static ToolChoice required() {
        return Mode.REQUIRED;
    }

    static ToolChoice specific(String toolName) {
        return new Specific(toolName);
    }
}
