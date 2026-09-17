package site.pplee.jcode.aiproviders.openai;

/**
 * OpenAI prompt-cache key limits. Truncation is by Unicode code point, not
 * UTF-16 code unit, so a supplementary-plane character is never split.
 */
final class OpenAiPromptCacheKeys {
    static final int MAX_CODE_POINTS = 64;

    private OpenAiPromptCacheKeys() {
    }

    static String clamp(String key) {
        if (key == null) {
            return null;
        }
        int codePoints = key.codePointCount(0, key.length());
        if (codePoints <= MAX_CODE_POINTS) {
            return key;
        }
        return key.substring(0, key.offsetByCodePoints(0, MAX_CODE_POINTS));
    }
}
