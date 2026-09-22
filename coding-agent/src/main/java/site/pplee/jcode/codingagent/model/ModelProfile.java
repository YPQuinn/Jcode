package site.pplee.jcode.codingagent.model;

import java.util.OptionalInt;

/** Optional product-level limits declared by configuration, not provider capability metadata. */
public record ModelProfile(
        OptionalInt contextWindow,
        OptionalInt maxOutputTokens
) {
    public ModelProfile {
        if (contextWindow == null || maxOutputTokens == null) {
            throw new NullPointerException("model profile limits must not be null");
        }
        if (contextWindow.isPresent() && contextWindow.getAsInt() <= 0) {
            throw new IllegalArgumentException("contextWindow must be positive");
        }
        if (maxOutputTokens.isPresent() && maxOutputTokens.getAsInt() <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }

    public static ModelProfile empty() {
        return new ModelProfile(OptionalInt.empty(), OptionalInt.empty());
    }
}
