package site.pplee.jcode.codingagent.settings;

import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.codingagent.tool.CodingTool;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Effective first-version settings and the winning source of each field. */
public record ResolvedSettings(
        Optional<ModelRef> defaultModel,
        ThinkingLevel defaultThinkingLevel,
        Set<CodingTool> defaultTools,
        QueueMode steeringMode,
        QueueMode followUpMode,
        ModelRequestOptions requestOptions,
        Map<SettingsField, SettingsSource> sources,
        boolean sdkModelOverride,
        boolean sdkThinkingOverride
) {
    public ResolvedSettings {
        Objects.requireNonNull(defaultModel, "defaultModel must not be null");
        Objects.requireNonNull(defaultThinkingLevel, "defaultThinkingLevel must not be null");
        defaultTools = Set.copyOf(Objects.requireNonNull(defaultTools, "defaultTools must not be null"));
        Objects.requireNonNull(steeringMode, "steeringMode must not be null");
        Objects.requireNonNull(followUpMode, "followUpMode must not be null");
        Objects.requireNonNull(requestOptions, "requestOptions must not be null");
        sources = Map.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
    }

    public Optional<SettingsSource> source(SettingsField field) {
        return Optional.ofNullable(sources.get(Objects.requireNonNull(field, "field must not be null")));
    }
}
