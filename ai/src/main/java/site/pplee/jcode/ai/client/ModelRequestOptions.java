package site.pplee.jcode.ai.client;

import java.util.Objects;

/**
 * Provider-neutral generation controls for one {@link ModelRequest}.
 * Unset numeric fields stay {@code null}; adapters omit those vendor
 * parameters rather than inventing a sentinel such as {@code 0}.
 *
 * <p>Validation is structural only. Whether a value is supported by a
 * particular model or endpoint is an adapter concern and must surface as a
 * terminal stream error, not a synchronous throw from {@link ModelRequest}.
 */
public record ModelRequestOptions(
        Integer maxOutputTokens,
        Double temperature,
        ToolChoice toolChoice,
        PromptCacheOptions promptCache
) {
    public ModelRequestOptions {
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be a positive integer when set");
        }
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0.0d)) {
            throw new IllegalArgumentException("temperature must be a finite non-negative number when set");
        }
        toolChoice = (toolChoice == null) ? ToolChoice.auto() : toolChoice;
        promptCache = (promptCache == null) ? PromptCacheOptions.defaults() : promptCache;
        Objects.requireNonNull(toolChoice, "toolChoice must not be null");
        Objects.requireNonNull(promptCache, "promptCache must not be null");
    }

    /** No explicit sampling, tool-choice, or cache overrides. */
    public static ModelRequestOptions defaults() {
        return new ModelRequestOptions(null, null, ToolChoice.auto(), PromptCacheOptions.defaults());
    }

    public ModelRequestOptions withMaxOutputTokens(Integer maxOutputTokens) {
        return new ModelRequestOptions(maxOutputTokens, temperature, toolChoice, promptCache);
    }

    public ModelRequestOptions withTemperature(Double temperature) {
        return new ModelRequestOptions(maxOutputTokens, temperature, toolChoice, promptCache);
    }

    public ModelRequestOptions withToolChoice(ToolChoice toolChoice) {
        return new ModelRequestOptions(maxOutputTokens, temperature, toolChoice, promptCache);
    }

    public ModelRequestOptions withPromptCache(PromptCacheOptions promptCache) {
        return new ModelRequestOptions(maxOutputTokens, temperature, toolChoice, promptCache);
    }
}
