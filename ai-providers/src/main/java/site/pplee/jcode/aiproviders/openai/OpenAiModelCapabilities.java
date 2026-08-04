package site.pplee.jcode.aiproviders.openai;

import site.pplee.jcode.ai.model.ThinkingLevel;

import java.util.Map;
import java.util.Objects;

/**
 * Provider-local capability metadata for one OpenAI model id: whether the
 * model supports reasoning, and the mapping from provider-neutral
 * {@link ThinkingLevel} values to OpenAI reasoning effort names. Kept out of
 * {@code site.pplee.jcode.ai.model.Model} so provider-specific capability
 * details stay in this module.
 */
public record OpenAiModelCapabilities(
        boolean reasoning,
        Map<ThinkingLevel, String> reasoningEfforts
) {
    public OpenAiModelCapabilities {
        reasoningEfforts = Map.copyOf(
                Objects.requireNonNull(reasoningEfforts, "reasoningEfforts must not be null"));
    }

    /** Capabilities for a model without reasoning support. */
    public static OpenAiModelCapabilities noReasoning() {
        return new OpenAiModelCapabilities(false, Map.of());
    }
}
