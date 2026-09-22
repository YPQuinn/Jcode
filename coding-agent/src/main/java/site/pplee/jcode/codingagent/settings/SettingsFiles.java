package site.pplee.jcode.codingagent.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.codingagent.tool.CodingTool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Explicit read-modify-write operations for global and authorized project settings. */
public final class SettingsFiles {
    private final Path projectDirectory;
    private final Path userConfigDirectory;
    private final ObjectMapper objectMapper;

    public SettingsFiles(
            Path projectDirectory,
            Path userConfigDirectory,
            ObjectMapper objectMapper
    ) throws IOException {
        this.projectDirectory = Objects.requireNonNull(
                projectDirectory, "projectDirectory must not be null").toRealPath();
        this.userConfigDirectory = Objects.requireNonNull(
                userConfigDirectory, "userConfigDirectory must not be null")
                .toAbsolutePath().normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public SettingsSaveResult updateGlobal(SettingsUpdate update) throws IOException {
        Objects.requireNonNull(update, "update must not be null");
        Path target = userConfigDirectory.resolve("settings.json");
        return update(target, update, true);
    }

    public SettingsSaveResult updateProject(
            SettingsUpdate update,
            ProjectTrustDecision effectiveDecision
    ) throws IOException {
        Objects.requireNonNull(update, "update must not be null");
        if (effectiveDecision != ProjectTrustDecision.ALLOW) {
            throw new IllegalStateException("project settings update requires an ALLOW decision");
        }
        Path target = projectDirectory.resolve(".jcode").resolve("settings.json");
        return update(target, update, false);
    }

    private SettingsSaveResult update(
            Path target,
            SettingsUpdate update,
            boolean privateFile
    ) throws IOException {
        if (update.isEmpty()) {
            return new SettingsSaveResult(target, false);
        }
        ConfigFileUpdater.update(target, objectMapper, privateFile, root -> {
            apply(root, update);
            try {
                SettingsLoader.parseSettings(root, target, new ArrayList<>());
            } catch (IllegalArgumentException failure) {
                throw new IOException("updated settings are invalid: " + failure.getMessage(), failure);
            }
        });
        return new SettingsSaveResult(target, true);
    }

    private static void apply(ObjectNode root, SettingsUpdate update) throws IOException {
        applyModel(root, "defaultModel", update.defaultModel());
        applyEnum(root, "defaultThinkingLevel", update.defaultThinkingLevel());
        applyTools(root, update.defaultTools());
        applyEnum(root, "steeringMode", update.steeringMode());
        applyEnum(root, "followUpMode", update.followUpMode());

        if (update.maxOutputTokens().operation() != SettingChange.Operation.KEEP
                || update.temperature().operation() != SettingChange.Operation.KEEP) {
            ObjectNode request;
            if (!root.has("request")) {
                request = root.putObject("request");
            } else if (root.get("request") instanceof ObjectNode object) {
                request = object;
            } else {
                throw new IOException("request must be an object before it can be updated");
            }
            applyNumber(request, "maxOutputTokens", update.maxOutputTokens());
            applyNumber(request, "temperature", update.temperature());
            if (request.isEmpty()) {
                root.remove("request");
            }
        }
        if (update.compactionEnabled().operation() != SettingChange.Operation.KEEP
                || update.compactionReserveTokens().operation() != SettingChange.Operation.KEEP
                || update.compactionKeepRecentTokens().operation() != SettingChange.Operation.KEEP) {
            ObjectNode compaction;
            if (!root.has("compaction")) {
                compaction = root.putObject("compaction");
            } else if (root.get("compaction") instanceof ObjectNode object) {
                compaction = object;
            } else {
                throw new IOException("compaction must be an object before it can be updated");
            }
            applyBoolean(compaction, "enabled", update.compactionEnabled());
            applyNumber(compaction, "reserveTokens", update.compactionReserveTokens());
            applyNumber(compaction, "keepRecentTokens", update.compactionKeepRecentTokens());
            if (compaction.isEmpty()) {
                root.remove("compaction");
            }
        }
    }

    private static void applyModel(
            ObjectNode root,
            String field,
            SettingChange<ModelRef> change
    ) {
        switch (change.operation()) {
            case KEEP -> { }
            case REMOVE -> root.remove(field);
            case SET -> {
                var model = change.value().orElseThrow();
                var node = root.putObject(field);
                node.put("provider", model.provider());
                node.put("api", model.api());
                node.put("modelId", model.modelId());
            }
        }
    }

    private static void applyTools(
            ObjectNode root,
            SettingChange<List<CodingTool>> change
    ) {
        switch (change.operation()) {
            case KEEP -> { }
            case REMOVE -> root.remove("defaultTools");
            case SET -> {
                ArrayNode tools = root.putArray("defaultTools");
                change.value().orElseThrow().forEach(tool -> tools.add(tool.toolName()));
            }
        }
    }

    private static void applyEnum(
            ObjectNode root,
            String field,
            SettingChange<? extends Enum<?>> change
    ) {
        switch (change.operation()) {
            case KEEP -> { }
            case REMOVE -> root.remove(field);
            case SET -> root.put(field,
                    change.value().orElseThrow().name().toLowerCase(Locale.ROOT));
        }
    }

    private static void applyNumber(
            ObjectNode root,
            String field,
            SettingChange<? extends Number> change
    ) {
        switch (change.operation()) {
            case KEEP -> { }
            case REMOVE -> root.remove(field);
            case SET -> {
                Number value = change.value().orElseThrow();
                if (value instanceof Integer integer) {
                    root.put(field, integer);
                } else {
                    root.put(field, value.doubleValue());
                }
            }
        }
    }

    private static void applyBoolean(
            ObjectNode root,
            String field,
            SettingChange<Boolean> change
    ) {
        switch (change.operation()) {
            case KEEP -> { }
            case REMOVE -> root.remove(field);
            case SET -> root.put(field, change.value().orElseThrow());
        }
    }
}
