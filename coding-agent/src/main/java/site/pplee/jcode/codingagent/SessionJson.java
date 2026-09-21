package site.pplee.jcode.codingagent;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** Strict field access used by the fixed version-one session codec. */
final class SessionJson {
    private SessionJson() {
    }

    static ObjectNode requireObject(JsonNode node, String location) {
        if (!(node instanceof ObjectNode object)) {
            throw invalid(location, "must be an object");
        }
        return object;
    }

    static ObjectNode requireObject(ObjectNode object, String field) {
        return requireObject(required(object, field), field);
    }

    static ObjectNode nullableObject(ObjectNode object, String field) {
        var value = required(object, field);
        return value.isNull() ? null : requireObject(value, field);
    }

    static ArrayNode requireArray(ObjectNode object, String field) {
        var value = required(object, field);
        if (!(value instanceof ArrayNode array)) {
            throw invalid(field, "must be an array");
        }
        return array;
    }

    static String requireText(ObjectNode object, String field) {
        var value = required(object, field);
        if (!value.isTextual()) {
            throw invalid(field, "must be a string");
        }
        return value.textValue();
    }

    static String nullableText(ObjectNode object, String field) {
        var value = required(object, field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field, "must be a string or null");
        }
        return value.textValue();
    }

    static long requireLong(ObjectNode object, String field) {
        var value = required(object, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(field, "must be a 64-bit integer");
        }
        return value.longValue();
    }

    static int requireInt(ObjectNode object, String field) {
        var value = required(object, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(field, "must be a 32-bit integer");
        }
        return value.intValue();
    }

    static boolean requireBoolean(ObjectNode object, String field) {
        var value = required(object, field);
        if (!value.isBoolean()) {
            throw invalid(field, "must be a boolean");
        }
        return value.booleanValue();
    }

    static BigDecimal requireDecimal(ObjectNode object, String field) {
        var value = required(object, field);
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isTextual()) {
            try {
                return new BigDecimal(value.textValue());
            } catch (NumberFormatException e) {
                throw invalid(field, "must contain a decimal value", e);
            }
        }
        throw invalid(field, "must be a decimal string or number");
    }

    static Instant requireInstant(ObjectNode object, String field) {
        var value = requireText(object, field);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(field, "must be an ISO-8601 instant", e);
        }
    }

    static JsonNode required(ObjectNode object, String field) {
        Objects.requireNonNull(object, "object must not be null");
        var value = object.get(field);
        if (value == null) {
            throw invalid(field, "is required");
        }
        return value;
    }

    static IllegalArgumentException invalid(String location, String reason) {
        return new IllegalArgumentException(location + " " + reason);
    }

    static IllegalArgumentException invalid(String location, String reason, Throwable cause) {
        return new IllegalArgumentException(location + " " + reason, cause);
    }
}
