package site.pplee.jcode.ai.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Provider-neutral constrained-sampling declaration for a {@link ToolSpec}.
 * Adapters map {@link JsonSchema} and {@link Grammar} onto provider payloads;
 * {@link None} means an ordinary function tool. Values are immutable and
 * validated at construction.
 */
public sealed interface ToolInputConstraint
        permits ToolInputConstraint.None,
                ToolInputConstraint.JsonSchema,
                ToolInputConstraint.Grammar {

    /** No constrained sampling; the tool is an ordinary function. */
    record None() implements ToolInputConstraint {
    }

    /**
     * Ask the provider to constrain arguments to the tool's JSON Schema
     * (for example OpenAI {@code strict: true}).
     */
    record JsonSchema(Requirement requirement) implements ToolInputConstraint {
        public JsonSchema {
            Objects.requireNonNull(requirement, "requirement must not be null");
        }
    }

    /**
     * Ask the provider to constrain a single string argument with a grammar.
     * Variant values are copied and must be non-blank; an empty map is
     * allowed here and rejected later if the adapter has no usable variant.
     */
    record Grammar(Map<GrammarSyntax, String> variants, Requirement requirement)
            implements ToolInputConstraint {
        public Grammar {
            Objects.requireNonNull(variants, "variants must not be null");
            Objects.requireNonNull(requirement, "requirement must not be null");
            var copy = new LinkedHashMap<GrammarSyntax, String>();
            for (var entry : variants.entrySet()) {
                GrammarSyntax syntax = Objects.requireNonNull(entry.getKey(), "variant syntax must not be null");
                String definition = Objects.requireNonNull(entry.getValue(), "variant definition must not be null");
                if (definition.isBlank()) {
                    throw new IllegalArgumentException("grammar variant " + syntax + " must not be blank");
                }
                copy.put(syntax, definition);
            }
            variants = Map.copyOf(copy);
        }
    }

    /** Shared empty constraint used by compatible {@link ToolSpec} constructors. */
    static None none() {
        return new None();
    }
}
