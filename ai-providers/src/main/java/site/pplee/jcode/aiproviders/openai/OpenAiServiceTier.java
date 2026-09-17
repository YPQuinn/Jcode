package site.pplee.jcode.aiproviders.openai;

import java.util.Optional;

/**
 * Explicit OpenAI Responses {@code service_tier}. This stays in provider
 * configuration and never enters the provider-neutral request options.
 */
public enum OpenAiServiceTier {
    AUTO("auto"),
    DEFAULT("default"),
    FLEX("flex"),
    PRIORITY("priority"),
    SCALE("scale");

    private final String wireValue;

    OpenAiServiceTier(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Value written to the Responses {@code service_tier} field. */
    public String wireValue() {
        return wireValue;
    }

    /** Parses a Responses {@code service_tier} wire value; unknown names are empty. */
    public static Optional<OpenAiServiceTier> fromWireValue(String wireValue) {
        if (wireValue == null || wireValue.isBlank()) {
            return Optional.empty();
        }
        for (OpenAiServiceTier tier : values()) {
            if (tier.wireValue.equals(wireValue)) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }
}
