package site.pplee.jcode.codingagent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.aiproviders.openai.OpenAiEndpointProfile;
import site.pplee.jcode.aiproviders.openai.OpenAiModelCapabilities;
import site.pplee.jcode.aiproviders.openai.OpenAiResponsesCompatibility;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Reads the fixed first-version {@code models.json} provider definition format. */
public final class ProviderDefinitions {
    private static final Set<String> PROVIDER_FIELDS = Set.of(
            "adapter", "name", "api", "baseUrl", "compatibility", "apiKeyEnv",
            "allowUnlistedModels", "models");
    private static final Set<String> MODEL_FIELDS = Set.of(
            "id", "name", "capabilities", "profile");
    private static final Set<String> CAPABILITY_FIELDS = Set.of(
            "reasoning", "reasoningEfforts", "imageInput", "developerRolePreferred",
            "temperature", "toolChoice");
    private static final Set<String> PROFILE_FIELDS = Set.of("contextWindow", "maxOutputTokens");
    private static final Set<String> COMPATIBILITY_FIELDS = Set.of(
            "developerRole", "endpointProfile", "maxOutputTokens", "promptCacheKey",
            "longCacheRetention", "strictTools", "grammarTools");

    private ProviderDefinitions() {
    }

    /** Missing files produce an empty valid definition set. */
    public static LoadResult load(Path path, ObjectMapper mapper) {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        var normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            return new LoadResult(List.of(), List.of(), true);
        }
        try (var input = Files.newInputStream(normalized)) {
            JsonNode root = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(input);
            if (root == null || !root.isObject() || root.size() != 1
                    || !root.has("providers") || !root.get("providers").isObject()) {
                throw new IllegalArgumentException(
                        "models file must contain only an object field named providers");
            }
            var definitions = new ArrayList<ProviderDefinition>();
            var providers = root.get("providers");
            providers.fields().forEachRemaining(entry ->
                    definitions.add(parseProvider(entry.getKey(), entry.getValue())));
            return new LoadResult(definitions, List.of(), true);
        } catch (JsonProcessingException failure) {
            return new LoadResult(List.of(), List.of(ModelAssemblyDiagnostic.file(
                    ModelAssemblyDiagnostic.Code.MODELS_INVALID,
                    "models file is not valid JSON", normalized)), false);
        } catch (IOException failure) {
            return new LoadResult(List.of(), List.of(ModelAssemblyDiagnostic.file(
                    ModelAssemblyDiagnostic.Code.MODELS_READ_FAILED,
                    "models file could not be read", normalized)), false);
        } catch (RuntimeException failure) {
            return new LoadResult(List.of(), List.of(ModelAssemblyDiagnostic.file(
                    ModelAssemblyDiagnostic.Code.MODELS_INVALID,
                    "models file was not applied", normalized)), false);
        }
    }

    /** SDK definitions replace file definitions with the same provider id. */
    public static List<ProviderDefinition> merge(
            List<ProviderDefinition> fileDefinitions,
            List<ProviderDefinition> sdkDefinitions
    ) {
        var merged = new LinkedHashMap<String, ProviderDefinition>();
        for (var definition : List.copyOf(fileDefinitions)) {
            if (merged.put(definition.providerId(), definition) != null) {
                throw new IllegalArgumentException(
                        "duplicate provider definition " + definition.providerId());
            }
        }
        var sdkIds = new HashSet<String>();
        for (var definition : List.copyOf(sdkDefinitions)) {
            if (!sdkIds.add(definition.providerId())) {
                throw new IllegalArgumentException(
                        "duplicate SDK provider definition " + definition.providerId());
            }
            merged.put(definition.providerId(), definition);
        }
        return List.copyOf(merged.values());
    }

    private static ProviderDefinition parseProvider(String providerId, JsonNode node) {
        requireObject(node, "provider " + providerId);
        requireKnownFields(node, PROVIDER_FIELDS, "provider " + providerId);
        String adapter = requireText(node, "adapter");
        if (!"openai-responses".equals(adapter)) {
            throw new IllegalArgumentException("unsupported adapter " + adapter);
        }
        String name = optionalText(node, "name").orElse(providerId);
        String api = requireText(node, "api");
        URI baseUrl = URI.create(requireText(node, "baseUrl"));
        var compatibility = parseCompatibility(node.get("compatibility"));
        var apiKeyEnv = optionalText(node, "apiKeyEnv");
        boolean allowUnlisted = optionalBoolean(node, "allowUnlistedModels", false);
        var modelsNode = node.get("models");
        if (modelsNode == null || !modelsNode.isArray()) {
            throw new IllegalArgumentException("provider " + providerId + ".models must be an array");
        }
        var models = new ArrayList<ProviderDefinition.DefinedModel>();
        for (var model : modelsNode) {
            models.add(parseModel(model));
        }
        return new ProviderDefinition(
                providerId, name, api, baseUrl, compatibility, apiKeyEnv,
                allowUnlisted, models);
    }

    private static ProviderDefinition.DefinedModel parseModel(JsonNode node) {
        requireObject(node, "model");
        requireKnownFields(node, MODEL_FIELDS, "model");
        String id = requireText(node, "id");
        String name = optionalText(node, "name").orElse(id);
        var capabilities = node.has("capabilities")
                ? parseCapabilities(node.get("capabilities"))
                : OpenAiModelCapabilities.noReasoning();
        var profile = node.has("profile")
                ? parseProfile(node.get("profile")) : ModelProfile.empty();
        return new ProviderDefinition.DefinedModel(id, name, capabilities, profile);
    }

    private static OpenAiModelCapabilities parseCapabilities(JsonNode node) {
        requireObject(node, "capabilities");
        requireKnownFields(node, CAPABILITY_FIELDS, "capabilities");
        boolean reasoning = optionalBoolean(node, "reasoning", false);
        var efforts = new EnumMap<ThinkingLevel, String>(ThinkingLevel.class);
        if (node.has("reasoningEfforts")) {
            var effortNode = node.get("reasoningEfforts");
            requireObject(effortNode, "reasoningEfforts");
            effortNode.fields().forEachRemaining(entry -> efforts.put(
                    parseEnum(entry.getKey(), ThinkingLevel.class, "reasoning effort"),
                    requireTextValue(entry.getValue(), "reasoning effort value")));
        }
        return new OpenAiModelCapabilities(
                reasoning,
                efforts,
                optionalBoolean(node, "imageInput", false),
                optionalBoolean(node, "developerRolePreferred", false),
                optionalBoolean(node, "temperature", false),
                optionalBoolean(node, "toolChoice", false));
    }

    private static ModelProfile parseProfile(JsonNode node) {
        requireObject(node, "profile");
        requireKnownFields(node, PROFILE_FIELDS, "profile");
        return new ModelProfile(
                optionalPositiveInt(node, "contextWindow"),
                optionalPositiveInt(node, "maxOutputTokens"));
    }

    private static OpenAiResponsesCompatibility parseCompatibility(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("compatibility is required");
        }
        if (node.isTextual()) {
            return switch (node.textValue()) {
                case "openai" -> OpenAiResponsesCompatibility.openai();
                case "force_system" -> OpenAiResponsesCompatibility.forceSystem();
                case "openai_no_session" -> OpenAiResponsesCompatibility.openaiNoSession();
                case "openrouter" -> OpenAiResponsesCompatibility.openRouter();
                default -> throw new IllegalArgumentException(
                        "unsupported compatibility " + node.textValue());
            };
        }
        requireObject(node, "compatibility");
        requireKnownFields(node, COMPATIBILITY_FIELDS, "compatibility");
        return new OpenAiResponsesCompatibility(
                optionalBoolean(node, "developerRole", false),
                parseEnum(requireText(node, "endpointProfile"),
                        OpenAiEndpointProfile.class, "endpointProfile"),
                optionalBoolean(node, "maxOutputTokens", false),
                optionalBoolean(node, "promptCacheKey", false),
                optionalBoolean(node, "longCacheRetention", false),
                optionalBoolean(node, "strictTools", false),
                optionalBoolean(node, "grammarTools", false));
    }

    private static OptionalInt optionalPositiveInt(JsonNode object, String field) {
        if (!object.has(field)) {
            return OptionalInt.empty();
        }
        var value = object.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        return OptionalInt.of(value.intValue());
    }

    private static boolean optionalBoolean(JsonNode object, String field, boolean fallback) {
        if (!object.has(field)) {
            return fallback;
        }
        var value = object.get(field);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value.booleanValue();
    }

    private static Optional<String> optionalText(JsonNode object, String field) {
        return object.has(field) ? Optional.of(requireText(object, field)) : Optional.empty();
    }

    private static String requireText(JsonNode object, String field) {
        return requireTextValue(object.get(field), field);
    }

    private static String requireTextValue(JsonNode value, String field) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static void requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
    }

    private static void requireKnownFields(JsonNode node, Set<String> fields, String location) {
        node.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) {
                throw new IllegalArgumentException(
                        location + " contains unsupported field " + field);
            }
        });
    }

    private static <E extends Enum<E>> E parseEnum(String text, Class<E> type, String field) {
        try {
            return Enum.valueOf(type, text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(field + " has unsupported value " + text, failure);
        }
    }

    public record LoadResult(
            List<ProviderDefinition> definitions,
            List<ModelAssemblyDiagnostic> diagnostics,
            boolean valid
    ) {
        public LoadResult {
            definitions = List.copyOf(definitions);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
