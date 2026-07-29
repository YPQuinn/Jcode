package site.pplee.jcode.agentcore;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Minimal JSON Schema validator covering the subset needed by the prepare
 * phase: {@code type}, {@code required}, and {@code properties} (recursive).
 * A {@code null} or empty schema is treated as "no validation" (everything
 * passes). Intentionally avoids pulling in a full JSON Schema library.
 */
final class ToolSchemaValidator {
    private ToolSchemaValidator() {}

    private static final Set<String> TYPE_NAMES = Set.of(
            "object", "string", "number", "integer", "boolean", "array", "null");

    /** Result of a validation pass. */
    record Result(boolean valid, String error) {
        static final Result OK = new Result(true, null);

        static Result fail(String error) {
            return new Result(false, error);
        }
    }

    /**
     * Validate {@code input} against {@code schema}.
     * Returns {@code Result.OK} when the schema is null/empty (no validation)
     * or when validation passes.
     */
    static Result validate(JsonNode input, JsonNode schema) {
        if (schema == null || schema.isNull() || (schema.isObject() && schema.isEmpty())) {
            return Result.OK;
        }
        return validateNode(input, schema, "");
    }

    private static Result validateNode(JsonNode input, JsonNode schema, String path) {
        // A boolean schema: true = valid, false = invalid
        if (schema.isBoolean()) {
            return schema.booleanValue() ? Result.OK : Result.fail(path + ": schema is false");
        }

        var typeNode = schema.get("type");
        if (typeNode != null) {
            var typeResult = checkType(input, typeNode, path);
            if (!typeResult.valid()) {
                return typeResult;
            }
        }

        var requiredResult = checkRequired(input, schema, path);
        if (!requiredResult.valid()) {
            return requiredResult;
        }

        var propsResult = checkProperties(input, schema, path);
        if (!propsResult.valid()) {
            return propsResult;
        }

        return Result.OK;
    }

    private static Result checkType(JsonNode input, JsonNode typeNode, String path) {
        String expectedType;
        if (typeNode.isTextual()) {
            expectedType = typeNode.asText();
        } else if (typeNode.isArray()) {
            for (var t : typeNode) {
                if (t.isTextual() && typeMatches(input, t.asText())) {
                    return Result.OK;
                }
            }
            var types = new ArrayList<String>();
            for (var t : typeNode) { types.add(t.asText()); }
            return Result.fail(path + ": expected one of " + types + ", got " + jsonType(input));
        } else {
            return Result.OK;
        }

        if (!TYPE_NAMES.contains(expectedType)) {
            return Result.OK;
        }
        if (!typeMatches(input, expectedType)) {
            return Result.fail(path + ": expected " + expectedType + ", got " + jsonType(input));
        }
        return Result.OK;
    }

    private static Result checkRequired(JsonNode input, JsonNode schema, String path) {
        var required = schema.get("required");
        if (required == null || !required.isArray() || !input.isObject()) {
            return Result.OK;
        }
        for (var field : required) {
            if (!input.has(field.asText())) {
                return Result.fail(path + ": missing required field \"" + field.asText() + "\"");
            }
        }
        return Result.OK;
    }

    private static Result checkProperties(JsonNode input, JsonNode schema, String path) {
        var properties = schema.get("properties");
        if (properties == null || !properties.isObject() || !input.isObject()) {
            return Result.OK;
        }
        for (var entry : List.copyOf(properties.properties())) {
            var fieldName = entry.getKey();
            if (input.has(fieldName)) {
                var subPath = path.isEmpty() ? fieldName : path + "." + fieldName;
                var result = validateNode(input.get(fieldName), entry.getValue(), subPath);
                if (!result.valid()) {
                    return result;
                }
            }
        }
        return Result.OK;
    }

    private static boolean typeMatches(JsonNode input, String expectedType) {
        return switch (expectedType) {
            case "object" -> input.isObject();
            case "string" -> input.isTextual();
            case "number" -> input.isNumber();
            case "integer" -> input.isIntegralNumber();
            case "boolean" -> input.isBoolean();
            case "array" -> input.isArray();
            case "null" -> input.isNull();
            default -> true;
        };
    }

    private static String jsonType(JsonNode node) {
        if (node.isObject()) return "object";
        if (node.isTextual()) return "string";
        if (node.isIntegralNumber()) return "integer";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        if (node.isArray()) return "array";
        if (node.isNull()) return "null";
        return "unknown";
    }
}
