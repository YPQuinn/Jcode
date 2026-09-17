package site.pplee.jcode.aiproviders.openai;

import site.pplee.jcode.ai.model.ThinkingLevel;

import java.util.Map;
import java.util.Objects;

/**
 * Provider-local capability metadata for one OpenAI model id: reasoning
 * support and effort mapping, whether the model accepts image input,
 * whether it prefers the {@code developer} prompt role, and whether it
 * accepts temperature or an explicit tool-choice. Endpoint support for
 * roles, cache, and session headers is decided separately by
 * {@link OpenAiResponsesCompatibility}. Kept out of
 * {@code site.pplee.jcode.ai.model.Model} so provider-specific details
 * stay in this module.
 */
public record OpenAiModelCapabilities(
        boolean reasoning,
        Map<ThinkingLevel, String> reasoningEfforts,
        boolean imageInput,
        boolean developerRolePreferred,
        boolean temperature,
        boolean toolChoice
) {
    public OpenAiModelCapabilities {
        reasoningEfforts = Map.copyOf(
                Objects.requireNonNull(reasoningEfforts, "reasoningEfforts must not be null"));
    }

    /**
     * Compatibility constructor from the image/role batch. Temperature and
     * tool-choice stay off so existing callers keep conservative defaults.
     */
    public OpenAiModelCapabilities(
            boolean reasoning,
            Map<ThinkingLevel, String> reasoningEfforts,
            boolean imageInput,
            boolean developerRolePreferred
    ) {
        this(reasoning, reasoningEfforts, imageInput, developerRolePreferred, false, false);
    }

    /**
     * Compatibility constructor: reasoning only. Image input is off, the
     * model does not prefer {@code developer}, and temperature/tool-choice
     * stay unsupported.
     */
    public OpenAiModelCapabilities(boolean reasoning, Map<ThinkingLevel, String> reasoningEfforts) {
        this(reasoning, reasoningEfforts, false, false, false, false);
    }

    /** Capabilities for a model without reasoning, image input, or sampling extras. */
    public static OpenAiModelCapabilities noReasoning() {
        return new OpenAiModelCapabilities(false, Map.of());
    }
}
