package site.pplee.jcode.codingagent.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.codingagent.tool.CodingTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Loads explicit global/project settings and resolves project trust once. */
public final class SettingsLoader {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "defaultModel", "defaultThinkingLevel", "defaultTools",
            "steeringMode", "followUpMode", "request");
    private static final Set<String> REQUEST_FIELDS = Set.of(
            "maxOutputTokens", "temperature");
    private static final Set<String> OUT_OF_SCOPE_FIELDS = Set.of(
            "apiKey", "headers", "baseUrl", "credentialSource",
            "trust", "trustDecision", "projectTrust", "projectTrustDecision");

    private SettingsLoader() {
    }

    public static SettingsLoadResult load(SettingsLoadRequest request) throws IOException {
        Path project = request.workingDirectory().toRealPath();
        var diagnostics = new ArrayList<SettingsDiagnostic>();
        var global = CodingAgentSettings.empty();
        if (request.userConfigDirectory().isPresent()) {
            var path = request.userConfigDirectory().orElseThrow().resolve("settings.json");
            var layer = readLayer(path, request.objectMapper());
            diagnostics.addAll(layer.diagnostics());
            if (layer.valid()) {
                global = layer.settings();
            }
        }

        ProjectTrustDecision decision = request.projectTrust();
        ProjectTrustSource trustSource;
        if (decision != ProjectTrustDecision.UNSPECIFIED) {
            trustSource = ProjectTrustSource.SDK;
        } else if (request.userConfigDirectory().isPresent()) {
            var lookup = new ProjectTrustStore(
                    request.userConfigDirectory().orElseThrow(), request.objectMapper())
                    .lookup(project);
            diagnostics.addAll(lookup.diagnostics());
            decision = lookup.decision();
            trustSource = decision == ProjectTrustDecision.UNSPECIFIED
                    ? ProjectTrustSource.NONE : ProjectTrustSource.STORE;
        } else {
            trustSource = ProjectTrustSource.NONE;
        }

        var projectSettings = CodingAgentSettings.empty();
        boolean projectApplied = false;
        Path projectFile = project.resolve(".jcode").resolve("settings.json");
        if (decision == ProjectTrustDecision.ALLOW) {
            var layer = readLayer(projectFile, request.objectMapper());
            diagnostics.addAll(layer.diagnostics());
            if (layer.valid()) {
                projectSettings = layer.settings();
                projectApplied = true;
            }
        } else {
            diagnostics.add(SettingsDiagnostic.of(
                    SettingsDiagnostic.Code.PROJECT_SETTINGS_NOT_APPLIED,
                    "project settings were not loaded because the project is not authorized",
                    projectFile));
        }

        var resolved = SettingsResolver.resolve(global, projectSettings, request.overrides());
        return new SettingsLoadResult(
                resolved, project, decision, trustSource, projectApplied, diagnostics);
    }

    private static LayerResult readLayer(Path path, ObjectMapper mapper) {
        if (!Files.exists(path)) {
            return new LayerResult(CodingAgentSettings.empty(), true, List.of());
        }
        var diagnostics = new ArrayList<SettingsDiagnostic>();
        try (var input = Files.newInputStream(path)) {
            JsonNode root = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(input);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("settings root must be an object");
            }
            var settings = parseSettings(root, path, diagnostics);
            return new LayerResult(settings, true, diagnostics);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            diagnostics.add(SettingsDiagnostic.of(
                    SettingsDiagnostic.Code.SETTINGS_INVALID,
                    "settings layer was not applied because it is invalid",
                    path));
            return new LayerResult(CodingAgentSettings.empty(), false, diagnostics);
        } catch (IOException failure) {
            diagnostics.add(SettingsDiagnostic.of(
                    SettingsDiagnostic.Code.SETTINGS_READ_FAILED,
                    "settings layer could not be read",
                    path));
            return new LayerResult(CodingAgentSettings.empty(), false, diagnostics);
        }
    }

    static CodingAgentSettings parseSettings(
            JsonNode root,
            Path path,
            List<SettingsDiagnostic> diagnostics
    ) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("settings root must be an object");
        }
        reportUnknownFields(root, ROOT_FIELDS, path, diagnostics, "settings");
        var builder = CodingAgentSettings.builder();
        if (root.has("defaultModel")) {
            var model = requireObject(root.get("defaultModel"), "defaultModel");
            reportUnknownFields(
                    model, Set.of("provider", "api", "modelId"),
                    path, diagnostics, "defaultModel");
            requireExactFields(model, Set.of("provider", "api", "modelId"), "defaultModel");
            builder.defaultModel(new ModelRef(
                    requireText(model, "provider"),
                    requireText(model, "api"),
                    requireText(model, "modelId")));
        }
        if (root.has("defaultThinkingLevel")) {
            builder.defaultThinkingLevel(parseEnum(
                    requireTextValue(root.get("defaultThinkingLevel"), "defaultThinkingLevel"),
                    ThinkingLevel.class,
                    "defaultThinkingLevel"));
        }
        if (root.has("defaultTools")) {
            var node = root.get("defaultTools");
            if (!node.isArray()) {
                throw new IllegalArgumentException("defaultTools must be an array");
            }
            var tools = new ArrayList<CodingTool>();
            var seen = new HashSet<CodingTool>();
            for (var item : node) {
                String name = requireTextValue(item, "defaultTools item");
                var tool = findTool(name);
                if (!seen.add(tool)) {
                    throw new IllegalArgumentException("defaultTools contains duplicate " + name);
                }
                tools.add(tool);
            }
            builder.defaultTools(tools);
        }
        if (root.has("steeringMode")) {
            builder.steeringMode(parseEnum(
                    requireTextValue(root.get("steeringMode"), "steeringMode"),
                    QueueMode.class,
                    "steeringMode"));
        }
        if (root.has("followUpMode")) {
            builder.followUpMode(parseEnum(
                    requireTextValue(root.get("followUpMode"), "followUpMode"),
                    QueueMode.class,
                    "followUpMode"));
        }
        if (root.has("request")) {
            var request = requireObject(root.get("request"), "request");
            reportUnknownFields(request, REQUEST_FIELDS, path, diagnostics, "request");
            if (request.has("maxOutputTokens")) {
                var value = request.get("maxOutputTokens");
                if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                    throw new IllegalArgumentException("request.maxOutputTokens must be an integer");
                }
                builder.maxOutputTokens(value.intValue());
            }
            if (request.has("temperature")) {
                var value = request.get("temperature");
                if (!value.isNumber()) {
                    throw new IllegalArgumentException("request.temperature must be a number");
                }
                builder.temperature(value.doubleValue());
            }
        }
        return builder.build();
    }

    private static void reportUnknownFields(
            JsonNode object,
            Set<String> known,
            Path path,
            List<SettingsDiagnostic> diagnostics,
            String prefix
    ) {
        boolean outOfScope = false;
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (known.contains(name)) {
                continue;
            }
            if (OUT_OF_SCOPE_FIELDS.contains(name)) {
                outOfScope = true;
                diagnostics.add(SettingsDiagnostic.of(
                        SettingsDiagnostic.Code.SETTINGS_FIELD_OUT_OF_SCOPE,
                        "settings field is not allowed in this file: " + prefix + "." + name,
                        path));
            } else {
                diagnostics.add(SettingsDiagnostic.of(
                        SettingsDiagnostic.Code.UNSUPPORTED_FIELD,
                        "unsupported settings field was not applied: " + prefix + "." + name,
                        path));
            }
        }
        if (outOfScope) {
            throw new IllegalArgumentException(
                    "settings contain fields that are not allowed in this file");
        }
    }

    private static JsonNode requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return node;
    }

    private static void requireExactFields(JsonNode object, Set<String> names, String field) {
        var actual = new HashSet<String>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(names)) {
            throw new IllegalArgumentException(field + " must contain provider, api, and modelId only");
        }
    }

    private static String requireText(JsonNode object, String field) {
        return requireTextValue(object.get(field), field);
    }

    private static String requireTextValue(JsonNode node, String field) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return node.textValue();
    }

    private static <E extends Enum<E>> E parseEnum(String text, Class<E> type, String field) {
        try {
            return Enum.valueOf(type, text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(field + " has unsupported value " + text, failure);
        }
    }

    private static CodingTool findTool(String name) {
        for (var tool : CodingTool.values()) {
            if (tool.toolName().equals(name)) {
                return tool;
            }
        }
        throw new IllegalArgumentException("defaultTools contains unknown tool " + name);
    }

    private record LayerResult(
            CodingAgentSettings settings,
            boolean valid,
            List<SettingsDiagnostic> diagnostics
    ) {
        private LayerResult {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
