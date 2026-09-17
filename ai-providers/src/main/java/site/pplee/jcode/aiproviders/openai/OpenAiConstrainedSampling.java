package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.tool.GrammarSyntax;
import site.pplee.jcode.ai.tool.Requirement;
import site.pplee.jcode.ai.tool.ToolInputConstraint;
import site.pplee.jcode.ai.tool.ToolSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-local resolution of {@link ToolInputConstraint} into OpenAI
 * Responses function/custom-tool payloads and streamed custom-tool JSON.
 * Mapping failures throw {@link IllegalArgumentException}; stream protocol
 * violations throw {@link IllegalStateException}.
 */
final class OpenAiConstrainedSampling {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> UNSUPPORTED_STRICT_KEYS = Set.of(
            "$ref",
            "$defs",
            "definitions",
            "allOf",
            "oneOf",
            "patternProperties",
            "dependentSchemas",
            "dependencies",
            "unevaluatedProperties",
            "propertyNames",
            "contains",
            "prefixItems",
            "not",
            "if",
            "then",
            "else"
    );

    private OpenAiConstrainedSampling() {
    }

    /**
     * Tools that will actually be sent as Responses custom tools, keyed by
     * tool name to the single string argument property.
     */
    static Map<String, String> grammarInputProperties(List<ToolSpec> tools, boolean grammarSupported) {
        var properties = new LinkedHashMap<String, String>();
        for (ToolSpec spec : tools) {
            OpenAiRequestUnicode.requireWellFormedIdentity(spec.name(), "tool name");
        }
        for (ToolSpec spec : tools) {
            ResolvedGrammar grammar = resolveGrammar(spec, grammarSupported);
            if (grammar != null) {
                properties.put(spec.name(), grammar.inputProperty());
            }
        }
        return Map.copyOf(properties);
    }

    static ObjectNode mapTool(ObjectMapper mapper, ToolSpec spec, OpenAiResponsesCompatibility compatibility) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(compatibility, "compatibility must not be null");
        OpenAiRequestUnicode.requireWellFormedIdentity(spec.name(), "tool name");
        ResolvedGrammar grammar = resolveGrammar(spec, compatibility.grammarTools());
        if (grammar != null) {
            return customGrammarTool(mapper, spec, grammar);
        }
        return functionTool(mapper, spec, resolveStrict(spec, compatibility.strictTools()));
    }

    static String grammarToolInput(String toolName, JsonNode arguments, String inputProperty) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(inputProperty, "inputProperty must not be null");
        JsonNode value = arguments == null ? null : arguments.get(inputProperty);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(
                    "Grammar tool call \"" + toolName + "\" requires argument \""
                            + inputProperty + "\" to be a string");
        }
        return OpenAiRequestUnicode.sanitizeText(value.asText());
    }

    static ObjectNode argumentsObject(ObjectMapper mapper, String inputProperty, String input) {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put(inputProperty, input == null ? "" : input);
        return arguments;
    }

    private static ObjectNode customGrammarTool(ObjectMapper mapper, ToolSpec spec, ResolvedGrammar grammar) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "custom");
        tool.put("name", spec.name());
        tool.put("description", OpenAiRequestUnicode.sanitizeText(spec.description()));
        ObjectNode format = tool.putObject("format");
        format.put("type", "grammar");
        format.put("syntax", grammarWireSyntax(grammar.syntax()));
        format.put("definition", OpenAiRequestUnicode.sanitizeText(grammar.definition()));
        return tool;
    }

    private static ObjectNode functionTool(ObjectMapper mapper, ToolSpec spec, JsonNode strictParameters) {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");
        tool.put("name", spec.name());
        tool.put("description", OpenAiRequestUnicode.sanitizeText(spec.description()));
        JsonNode parameters = strictParameters != null ? strictParameters : spec.parameters();
        if (parameters == null || parameters.isNull()) {
            tool.set("parameters", mapper.createObjectNode());
        } else if (parameters.isObject()) {
            tool.set("parameters", OpenAiRequestUnicode.copySanitized(mapper, parameters));
        } else {
            throw new IllegalArgumentException(
                    "tool " + spec.name() + " parameters must be an object schema but was "
                            + parameters.getNodeType());
        }
        if (strictParameters != null) {
            tool.put("strict", true);
        }
        return tool;
    }

    /**
     * Converted strict schema when the tool should send {@code strict: true};
     * {@code null} means emit an ordinary function payload.
     */
    static JsonNode resolveStrict(ToolSpec spec, boolean strictSupported) {
        if (!(spec.constraint() instanceof ToolInputConstraint.JsonSchema jsonSchema)) {
            return null;
        }
        if (strictSupported) {
            try {
                return makeStrictJsonSchema(spec.parameters());
            } catch (UnsupportedStrictJsonSchemaException e) {
                if (jsonSchema.requirement() == Requirement.REQUIRE) {
                    throw new IllegalArgumentException(
                            "Tool \"" + spec.name()
                                    + "\" requires JSON-schema constrained sampling, but "
                                    + e.getMessage() + ".");
                }
                return null;
            }
        }
        if (jsonSchema.requirement() == Requirement.REQUIRE) {
            throw new IllegalArgumentException(
                    "Tool \"" + spec.name()
                            + "\" requires JSON-schema constrained sampling, but strict tools are unsupported.");
        }
        return null;
    }

    static ResolvedGrammar resolveGrammar(ToolSpec spec, boolean grammarSupported) {
        if (!(spec.constraint() instanceof ToolInputConstraint.Grammar grammar)) {
            return null;
        }
        if (!grammarSupported) {
            if (grammar.requirement() == Requirement.REQUIRE) {
                throw new IllegalArgumentException(
                        "Tool \"" + spec.name()
                                + "\" requires grammar constrained sampling, but grammar tools are unsupported.");
            }
            return null;
        }
        String lark = usableVariant(grammar.variants().get(GrammarSyntax.LARK));
        String regex = usableVariant(grammar.variants().get(GrammarSyntax.REGEX));
        if (lark == null && regex == null) {
            throw new IllegalArgumentException(
                    "Tool \"" + spec.name()
                            + "\" cannot use grammar constrained sampling: no supported grammar variant was provided.");
        }
        try {
            return new ResolvedGrammar(
                    lark != null ? GrammarSyntax.LARK : GrammarSyntax.REGEX,
                    lark != null ? lark : regex,
                    inferGrammarInputProperty(spec.parameters()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Tool \"" + spec.name() + "\" cannot use grammar constrained sampling: "
                            + e.getMessage() + ".",
                    e);
        }
    }

    static String inferGrammarInputProperty(JsonNode schema) {
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText(""))) {
            throw new IllegalArgumentException("grammar constrained sampling requires an object parameter schema");
        }
        JsonNode required = schema.get("required");
        if (required == null || !required.isArray() || required.size() != 1 || !required.get(0).isTextual()) {
            throw new IllegalArgumentException(
                    "grammar constrained sampling requires exactly one required string property");
        }
        String inputProperty = required.get(0).asText();
        OpenAiRequestUnicode.requireWellFormedIdentity(inputProperty, "JSON object field name");
        JsonNode property = schema.path("properties").get(inputProperty);
        if (property == null || property.isMissingNode() || property.isNull()) {
            throw new IllegalArgumentException(
                    "grammar constrained sampling requires a properties entry for " + inputProperty);
        }
        if (!"string".equals(property.path("type").asText(""))) {
            throw new IllegalArgumentException(
                    "grammar constrained sampling property " + inputProperty + " must have type string");
        }
        return inputProperty;
    }

    static JsonNode makeStrictJsonSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            throw new UnsupportedStrictJsonSchemaException("root schema must have type object");
        }
        JsonNode cloned = schema.deepCopy();
        makeJsonSchemaNodeStrict(cloned);
        if (!cloned.isObject() || !"object".equals(cloned.path("type").asText(""))) {
            throw new UnsupportedStrictJsonSchemaException("root schema must have type object");
        }
        return cloned;
    }

    private static void makeJsonSchemaNodeStrict(JsonNode schema) {
        if (schema == null || schema.isBoolean()) {
            throw new UnsupportedStrictJsonSchemaException("boolean schemas are unsupported");
        }
        if (!schema.isObject()) {
            throw new UnsupportedStrictJsonSchemaException("boolean schemas are unsupported");
        }
        ObjectNode object = (ObjectNode) schema;
        for (String key : UNSUPPORTED_STRICT_KEYS) {
            if (object.has(key)) {
                throw new UnsupportedStrictJsonSchemaException(key + " schemas are unsupported");
            }
        }
        if (object.has("anyOf")) {
            JsonNode anyOf = object.get("anyOf");
            if (!anyOf.isArray() || anyOf.isEmpty()) {
                throw new UnsupportedStrictJsonSchemaException("anyOf must contain at least one schema");
            }
            for (JsonNode variant : anyOf) {
                if (isStructuredSchema(variant)) {
                    throw new UnsupportedStrictJsonSchemaException("object and array unions are unsupported");
                }
                makeJsonSchemaNodeStrict(variant);
            }
        }
        if (object.has("items")) {
            JsonNode items = object.get("items");
            if (items.isArray()) {
                throw new UnsupportedStrictJsonSchemaException("tuple schemas are unsupported");
            }
            makeJsonSchemaNodeStrict(items);
        }
        boolean objectSchema = "object".equals(object.path("type").asText(""));
        if (object.has("properties") && !objectSchema) {
            throw new UnsupportedStrictJsonSchemaException("properties require type object");
        }
        if (!objectSchema) {
            return;
        }
        if (object.has("additionalProperties")) {
            JsonNode additional = object.get("additionalProperties");
            if (!additional.isBoolean() || additional.asBoolean()) {
                throw new UnsupportedStrictJsonSchemaException(
                        "schema-valued or true additionalProperties is unsupported");
            }
        }
        if (object.has("properties") && !object.get("properties").isObject()) {
            throw new UnsupportedStrictJsonSchemaException("object properties must be a schema map");
        }
        if (object.has("required")) {
            JsonNode required = object.get("required");
            if (!required.isArray()) {
                throw new UnsupportedStrictJsonSchemaException("object required must be a string array");
            }
            for (JsonNode key : required) {
                if (!key.isTextual()) {
                    throw new UnsupportedStrictJsonSchemaException("object required must be a string array");
                }
            }
        }
        ObjectNode properties = object.has("properties") && object.get("properties").isObject()
                ? (ObjectNode) object.get("properties")
                : object.putObject("properties");
        var required = new java.util.LinkedHashSet<String>();
        if (object.has("required") && object.get("required").isArray()) {
            for (JsonNode key : object.get("required")) {
                required.add(key.asText());
            }
        }
        var propertyNames = new java.util.ArrayList<String>();
        properties.fieldNames().forEachRemaining(propertyNames::add);
        for (String key : required) {
            if (!propertyNames.contains(key)) {
                throw new UnsupportedStrictJsonSchemaException("required contains an unknown property");
            }
        }
        for (String key : propertyNames) {
            JsonNode property = properties.get(key);
            makeJsonSchemaNodeStrict(property);
            if (!required.contains(key) && !schemaAllowsNull(property)) {
                ArrayNode anyOf = MAPPER.createArrayNode();
                anyOf.add(property.deepCopy());
                anyOf.addObject().put("type", "null");
                properties.set(key, MAPPER.createObjectNode().set("anyOf", anyOf));
            }
        }
        ArrayNode requiredArray = object.putArray("required");
        for (String key : propertyNames) {
            requiredArray.add(key);
        }
        object.put("additionalProperties", false);
    }

    private static boolean isStructuredSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return false;
        }
        if (typeIncludes(schema, "object") || typeIncludes(schema, "array")) {
            return true;
        }
        return schema.has("properties") || schema.has("items");
    }

    private static boolean schemaAllowsNull(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return false;
        }
        if (typeIncludes(schema, "null")) {
            return true;
        }
        if (schema.has("const") && schema.get("const").isNull()) {
            return true;
        }
        if (schema.has("enum") && schema.get("enum").isArray()) {
            for (JsonNode value : schema.get("enum")) {
                if (value.isNull()) {
                    return true;
                }
            }
        }
        if (schema.has("anyOf") && schema.get("anyOf").isArray()) {
            for (JsonNode variant : schema.get("anyOf")) {
                if (schemaAllowsNull(variant)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean typeIncludes(JsonNode schema, String type) {
        JsonNode node = schema.get("type");
        if (node == null) {
            return false;
        }
        if (node.isTextual()) {
            return type.equals(node.asText());
        }
        if (node.isArray()) {
            for (JsonNode entry : node) {
                if (type.equals(entry.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** OpenAI Responses grammar syntax token. Kept here so {@code ai} stays provider-neutral. */
    static String grammarWireSyntax(GrammarSyntax syntax) {
        return switch (syntax) {
            case LARK -> "lark";
            case REGEX -> "regex";
        };
    }

    private static String usableVariant(String definition) {
        return definition == null || definition.isBlank() ? null : definition;
    }

    static String escapeJsonStringContents(String value) {
        try {
            String quoted = MAPPER.writeValueAsString(value == null ? "" : value);
            return quoted.substring(1, quoted.length() - 1);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to escape grammar tool input", e);
        }
    }

    record ResolvedGrammar(GrammarSyntax syntax, String definition, String inputProperty) {
        ResolvedGrammar {
            Objects.requireNonNull(syntax, "syntax must not be null");
            Objects.requireNonNull(definition, "definition must not be null");
            Objects.requireNonNull(inputProperty, "inputProperty must not be null");
        }
    }

    static final class GrammarToolInputJsonBuffer {
        private String input = "";
        private boolean started;
        private boolean closed;

        String input() {
            return input;
        }

        boolean closed() {
            return closed;
        }

        /**
         * Appends {@code nextInput} and returns the JSON object-fragment delta,
         * or {@code null} when there is nothing new to emit.
         */
        String append(String inputProperty, String nextInput, boolean close) {
            Objects.requireNonNull(inputProperty, "inputProperty must not be null");
            Objects.requireNonNull(nextInput, "nextInput must not be null");
            if (closed) {
                if (close && nextInput.equals(input)) {
                    return null;
                }
                throw new IllegalStateException(
                        "grammar tool input for property \"" + inputProperty + "\" changed after it was closed");
            }
            if (!nextInput.startsWith(input)) {
                throw new IllegalStateException(
                        "grammar tool input for property \"" + inputProperty + "\" changed non-monotonically");
            }
            String inputDelta = nextInput.substring(input.length());
            if (!close && inputDelta.isEmpty()) {
                return null;
            }
            var delta = new StringBuilder();
            if (!started) {
                delta.append('{').append(quote(inputProperty)).append(":\"");
                started = true;
            }
            delta.append(escapeJsonStringContents(inputDelta));
            input = nextInput;
            if (close) {
                delta.append("\"}");
                closed = true;
            }
            return delta.toString();
        }

        private static String quote(String value) {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("failed to quote grammar input property", e);
            }
        }
    }

    static final class UnsupportedStrictJsonSchemaException extends IllegalArgumentException {
        UnsupportedStrictJsonSchemaException(String message) {
            super(message);
        }
    }
}
