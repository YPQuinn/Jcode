package site.pplee.jcode.agentcore.tool;

import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.tool.ToolInputConstraint;
import site.pplee.jcode.ai.tool.ToolSpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.concurrent.CompletionStage;

/**
 * Callable tool used by the agent runtime. Combines the declarable
 * {@link ToolSpec} (name, description, JSON Schema) — which is what the model
 * sees and what lives in {@code ai} — with execution and update capability,
 * which is an agent-runtime concern.
 *
 * <p>The three-phase pipeline (prepare / execute / finalize) uses:
 * <ul>
 *   <li>{@link #prepareArguments} to preprocess raw arguments before validation;
 *   <li>{@link #parametersSchema} to validate the prepared arguments;
 *   <li>{@link #execute} to run the tool, reporting progress via
 *       {@link ToolUpdateSink}.
 * </ul>
 *
 * <p>{@link #spec()} defaults to a minimal spec built from {@link #name()},
 * an empty description, and a null schema; concrete tools override
 * {@link #description()} and/or {@link #parametersSchema()} to declare a real
 * schema. This keeps the standard {@code ai.ToolSpec} the single source of
 * truth for what the model is told, while execution stays in
 * {@code agent-core} (see architecture §3.2 and §2.5).
 *
 * <p>The core layer converts the model's raw {@link JsonNode} arguments to
 * {@code A} via the shared {@link com.fasterxml.jackson.databind.ObjectMapper};
 * any conversion, validation, or execution failure becomes an error
 * {@link ToolExecutionResult} and is never thrown into the loop.
 */
public interface AgentTool<A> {
    /** Stable tool name the model uses to request it (also the {@link ToolSpec} name). */
    String name();

    /** Strong type the raw arguments are converted into. */
    Class<A> argumentType();

    /** Human-readable description shown to the model; empty by default. */
    default String description() {
        return "";
    }

    /** JSON Schema describing the arguments; a null node by default (no validation). */
    default JsonNode parametersSchema() {
        return NullNode.getInstance();
    }

    /**
     * Constrained-sampling declaration copied into {@link #spec()}. Defaults
     * to {@link ToolInputConstraint#none()}; override to request JSON-schema
     * or grammar constraints without replacing {@link #spec()}.
     */
    default ToolInputConstraint constraint() {
        return ToolInputConstraint.none();
    }

    /**
     * Preprocess raw arguments before schema validation and type conversion.
     * Default returns the arguments unchanged. Override to inject defaults,
     * normalize field names, or coerce types.
     */
    default JsonNode prepareArguments(JsonNode arguments) {
        return arguments;
    }

    /** The declarable {@link ToolSpec} handed to the model. */
    default ToolSpec spec() {
        return new ToolSpec(name(), description(), parametersSchema(), constraint());
    }

    /** Default {@link ToolExecutionMode#PARALLEL}; override for sequential. */
    default ToolExecutionMode executionMode() {
        return ToolExecutionMode.PARALLEL;
    }

    /**
     * Run the tool; report progress via {@code updates} and return the final
     * outcome. Any exception is caught by the loop and converted to an error
     * {@link ToolExecutionResult}.
     */
    CompletionStage<ToolExecutionResult> execute(
            String toolCallId,
            A arguments,
            ToolUpdateSink updates,
            CancellationSignal cancellation
    );
}
