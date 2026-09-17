package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.util.UnicodeSanitizer;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Request-local Unicode policy for OpenAI Responses serialization.
 * Visible text is sanitized; structural identities fail closed. Callers
 * must never pass a transcript message, tool spec, schema, or argument
 * {@link JsonNode} that they intend to keep: every JsonNode rewrite is a
 * deep copy. Diagnostics never include the malformed value.
 */
final class OpenAiRequestUnicode {
    private OpenAiRequestUnicode() {
    }

    static String sanitizeText(String value) {
        return UnicodeSanitizer.removeUnpairedSurrogates(value);
    }

    static void requireWellFormedIdentity(String value, String kind) {
        Objects.requireNonNull(value, kind + " must not be null");
        if (!UnicodeSanitizer.isWellFormedUtf16(value)) {
            throw new IllegalArgumentException("malformed UTF-16 in " + kind);
        }
    }

    static void requireWellFormedModel(ModelRef model) {
        Objects.requireNonNull(model, "model must not be null");
        requireWellFormedIdentity(model.provider(), "provider id");
        requireWellFormedIdentity(model.api(), "api id");
        requireWellFormedIdentity(model.modelId(), "model id");
    }

    /**
     * Deep-copies {@code node}, sanitizing every textual value. Object
     * field names are identities and fail if they are not well-formed
     * UTF-16. Binary, numeric, and boolean nodes are copied as-is.
     */
    static JsonNode copySanitized(ObjectMapper mapper, JsonNode node) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        if (node.isTextual()) {
            return mapper.getNodeFactory().textNode(sanitizeText(node.textValue()));
        }
        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            for (JsonNode child : node) {
                copy.add(copySanitized(mapper, child));
            }
            return copy;
        }
        if (node.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                requireWellFormedIdentity(field.getKey(), "JSON object field name");
                copy.set(field.getKey(), copySanitized(mapper, field.getValue()));
            }
            return copy;
        }
        return node.deepCopy();
    }
}
