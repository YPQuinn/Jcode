package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Bounded, defensive parser for streamed tool-call argument prefixes.
 * Tries a strict Jackson parse first, then a single O(n) object-root repair
 * that only closes already-opened strings and containers. It never invents
 * field values or completes literals.
 */
final class OpenAiPartialJsonParser {
    static final int MAX_DEPTH = 128;
    static final int MAX_CHARS = 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAiPartialJsonParser() {
    }

    /**
     * Best-effort object for {@code raw}. Returns {@code previous} when the
     * prefix cannot be closed safely or is not an object root.
     */
    static JsonNode parse(String raw, JsonNode previous) {
        JsonNode strict = tryStrict(raw);
        if (isObject(strict)) {
            return strict;
        }
        if (raw == null || raw.length() > MAX_CHARS) {
            return previous;
        }
        String repaired = repairObjectPrefix(raw);
        JsonNode parsed = tryStrict(repaired);
        return isObject(parsed) ? parsed : previous;
    }

    private static JsonNode tryStrict(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static boolean isObject(JsonNode node) {
        return node != null && node.isObject();
    }

    private static String repairObjectPrefix(String raw) {
        String trimmed = raw.stripLeading();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            return null;
        }
        boolean inString = false;
        boolean escape = false;
        int depth = 0;
        char[] stack = new char[MAX_DEPTH];
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                    continue;
                }
                if (c == '\\') {
                    escape = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> {
                    if (depth >= MAX_DEPTH) {
                        return null;
                    }
                    stack[depth++] = '{';
                }
                case '[' -> {
                    if (depth >= MAX_DEPTH) {
                        return null;
                    }
                    stack[depth++] = '[';
                }
                case '}' -> {
                    if (depth == 0 || stack[depth - 1] != '{') {
                        return null;
                    }
                    depth--;
                }
                case ']' -> {
                    if (depth == 0 || stack[depth - 1] != '[') {
                        return null;
                    }
                    depth--;
                }
                default -> {
                    // literals, numbers, colon, comma
                }
            }
        }
        if (depth == 0 || escape) {
            return null;
        }
        var sb = new StringBuilder(raw);
        if (inString) {
            sb.append('"');
        }
        int end = sb.length() - 1;
        while (end >= 0 && Character.isWhitespace(sb.charAt(end))) {
            end--;
        }
        if (end >= 0 && sb.charAt(end) == ',') {
            sb.deleteCharAt(end);
        } else if (end >= 0 && sb.charAt(end) == ':') {
            return null;
        }
        for (int i = depth - 1; i >= 0; i--) {
            sb.append(stack[i] == '{' ? '}' : ']');
        }
        return sb.toString();
    }
}
