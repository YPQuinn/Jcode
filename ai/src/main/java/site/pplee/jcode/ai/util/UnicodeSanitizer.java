package site.pplee.jcode.ai.util;

import java.util.Objects;

/**
 * Stateless UTF-16 well-formedness helpers. A single linear scan removes
 * unpaired high ({@code U+D800}–{@code U+DBFF}) or low
 * ({@code U+DC00}–{@code U+DFFF}) surrogates. Valid surrogate pairs,
 * BMP characters, and combining sequences are left unchanged. The methods
 * perform no Unicode normalization and never mutate the input
 * {@link String}; a well-formed value is returned as the same instance.
 *
 * <p>Null is a caller-boundary error. These helpers do not interpret
 * provider payloads, identities, or transcripts.
 */
public final class UnicodeSanitizer {
    private UnicodeSanitizer() {
    }

    /**
     * {@code true} when every high surrogate is immediately followed by a
     * low surrogate and no low surrogate appears unpaired.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static boolean isWellFormedUtf16(String value) {
        Objects.requireNonNull(value, "value must not be null");
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (isHighSurrogate(c)) {
                if (i + 1 >= length || !isLowSurrogate(value.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code value} unchanged when it is well-formed UTF-16;
     * otherwise a new string with every unpaired surrogate removed.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String removeUnpairedSurrogates(String value) {
        Objects.requireNonNull(value, "value must not be null");
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (isHighSurrogate(c)) {
                if (i + 1 < length && isLowSurrogate(value.charAt(i + 1))) {
                    i++;
                    continue;
                }
                return stripUnpaired(value, i);
            }
            if (isLowSurrogate(c)) {
                return stripUnpaired(value, i);
            }
        }
        return value;
    }

    private static String stripUnpaired(String value, int firstInvalid) {
        int length = value.length();
        StringBuilder out = new StringBuilder(length);
        out.append(value, 0, firstInvalid);
        for (int i = firstInvalid; i < length; i++) {
            char c = value.charAt(i);
            if (isHighSurrogate(c)) {
                if (i + 1 < length && isLowSurrogate(value.charAt(i + 1))) {
                    out.append(c).append(value.charAt(i + 1));
                    i++;
                }
            } else if (!isLowSurrogate(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isHighSurrogate(char c) {
        return c >= '\uD800' && c <= '\uDBFF';
    }

    private static boolean isLowSurrogate(char c) {
        return c >= '\uDC00' && c <= '\uDFFF';
    }
}
