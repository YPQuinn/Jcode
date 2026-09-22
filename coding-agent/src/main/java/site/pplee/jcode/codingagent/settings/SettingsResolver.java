package site.pplee.jcode.codingagent.settings;

import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.codingagent.tool.CodingTool;
import site.pplee.jcode.codingagent.tool.CodingToolConfig;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;

/** Pure precedence resolver for global, authorized project, and SDK settings layers. */
public final class SettingsResolver {
    private SettingsResolver() {
    }

    public static ResolvedSettings resolve(
            CodingAgentSettings global,
            CodingAgentSettings project,
            SettingsOverrides overrides
    ) {
        Objects.requireNonNull(global, "global must not be null");
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(overrides, "overrides must not be null");

        var values = new Values();
        values.apply(global, SettingsSource.GLOBAL);
        values.apply(project, SettingsSource.PROJECT);
        values.apply(overrides.settings(), SettingsSource.SDK);

        ModelRequestOptions requestOptions;
        if (overrides.requestOptions().isPresent()) {
            requestOptions = overrides.requestOptions().orElseThrow();
            values.sources.put(SettingsField.REQUEST_MAX_OUTPUT_TOKENS, SettingsSource.SDK);
            values.sources.put(SettingsField.REQUEST_TEMPERATURE, SettingsSource.SDK);
        } else {
            requestOptions = ModelRequestOptions.defaults()
                    .withMaxOutputTokens(values.maxOutputTokens)
                    .withTemperature(values.temperature);
        }

        return new ResolvedSettings(
                Optional.ofNullable(values.model),
                values.thinkingLevel,
                values.tools,
                values.steeringMode,
                values.followUpMode,
                requestOptions,
                values.sources,
                overrides.settings().defaultModel().isPresent(),
                overrides.settings().defaultThinkingLevel().isPresent());
    }

    private static final class Values {
        private ModelRef model;
        private ThinkingLevel thinkingLevel = ThinkingLevel.PROVIDER_DEFAULT;
        private LinkedHashSet<CodingTool> tools = new LinkedHashSet<>(
                CodingToolConfig.readOnly().enabledTools());
        private QueueMode steeringMode = QueueMode.ONE_AT_A_TIME;
        private QueueMode followUpMode = QueueMode.ONE_AT_A_TIME;
        private Integer maxOutputTokens;
        private Double temperature;
        private final EnumMap<SettingsField, SettingsSource> sources =
                new EnumMap<>(SettingsField.class);

        private Values() {
            sources.put(SettingsField.DEFAULT_THINKING_LEVEL, SettingsSource.BUILT_IN);
            sources.put(SettingsField.DEFAULT_TOOLS, SettingsSource.BUILT_IN);
            sources.put(SettingsField.STEERING_MODE, SettingsSource.BUILT_IN);
            sources.put(SettingsField.FOLLOW_UP_MODE, SettingsSource.BUILT_IN);
            sources.put(SettingsField.REQUEST_MAX_OUTPUT_TOKENS, SettingsSource.BUILT_IN);
            sources.put(SettingsField.REQUEST_TEMPERATURE, SettingsSource.BUILT_IN);
        }

        private void apply(CodingAgentSettings layer, SettingsSource source) {
            layer.defaultModel().ifPresent(value -> {
                model = value;
                sources.put(SettingsField.DEFAULT_MODEL, source);
            });
            layer.defaultThinkingLevel().ifPresent(value -> {
                thinkingLevel = value;
                sources.put(SettingsField.DEFAULT_THINKING_LEVEL, source);
            });
            layer.defaultTools().ifPresent(value -> {
                tools = new LinkedHashSet<>(value);
                sources.put(SettingsField.DEFAULT_TOOLS, source);
            });
            layer.steeringMode().ifPresent(value -> {
                steeringMode = value;
                sources.put(SettingsField.STEERING_MODE, source);
            });
            layer.followUpMode().ifPresent(value -> {
                followUpMode = value;
                sources.put(SettingsField.FOLLOW_UP_MODE, source);
            });
            layer.maxOutputTokens().ifPresent(value -> {
                maxOutputTokens = value;
                sources.put(SettingsField.REQUEST_MAX_OUTPUT_TOKENS, source);
            });
            layer.temperature().ifPresent(value -> {
                temperature = value;
                sources.put(SettingsField.REQUEST_TEMPERATURE, source);
            });
        }
    }
}
