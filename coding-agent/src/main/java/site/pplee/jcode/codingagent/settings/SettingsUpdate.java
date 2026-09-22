package site.pplee.jcode.codingagent.settings;

import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.codingagent.tool.CodingTool;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Fixed, strongly typed update for the first-version settings fields. */
public record SettingsUpdate(
        SettingChange<ModelRef> defaultModel,
        SettingChange<ThinkingLevel> defaultThinkingLevel,
        SettingChange<List<CodingTool>> defaultTools,
        SettingChange<QueueMode> steeringMode,
        SettingChange<QueueMode> followUpMode,
        SettingChange<Integer> maxOutputTokens,
        SettingChange<Double> temperature,
        SettingChange<Boolean> compactionEnabled,
        SettingChange<Integer> compactionReserveTokens,
        SettingChange<Integer> compactionKeepRecentTokens
) {
    public SettingsUpdate {
        Objects.requireNonNull(defaultModel, "defaultModel must not be null");
        Objects.requireNonNull(defaultThinkingLevel, "defaultThinkingLevel must not be null");
        Objects.requireNonNull(defaultTools, "defaultTools must not be null");
        Objects.requireNonNull(steeringMode, "steeringMode must not be null");
        Objects.requireNonNull(followUpMode, "followUpMode must not be null");
        Objects.requireNonNull(maxOutputTokens, "maxOutputTokens must not be null");
        Objects.requireNonNull(temperature, "temperature must not be null");
        Objects.requireNonNull(compactionEnabled, "compactionEnabled must not be null");
        Objects.requireNonNull(compactionReserveTokens, "compactionReserveTokens must not be null");
        Objects.requireNonNull(compactionKeepRecentTokens, "compactionKeepRecentTokens must not be null");
        defaultTools = copyListChange(defaultTools);
        defaultTools.value().ifPresent(tools -> {
            if (new HashSet<>(tools).size() != tools.size()) {
                throw new IllegalArgumentException("defaultTools must not contain duplicates");
            }
        });
        maxOutputTokens.value().ifPresent(value -> {
            if (value <= 0) {
                throw new IllegalArgumentException("maxOutputTokens must be positive");
            }
        });
        temperature.value().ifPresent(value -> {
            if (!Double.isFinite(value) || value < 0.0d) {
                throw new IllegalArgumentException("temperature must be finite and non-negative");
            }
        });
        compactionReserveTokens.value().ifPresent(value -> requirePositive(value, "compactionReserveTokens"));
        compactionKeepRecentTokens.value().ifPresent(value -> requirePositive(value, "compactionKeepRecentTokens"));
    }

    /** Compatibility constructor for updates created before compaction existed. */
    public SettingsUpdate(
            SettingChange<ModelRef> defaultModel,
            SettingChange<ThinkingLevel> defaultThinkingLevel,
            SettingChange<List<CodingTool>> defaultTools,
            SettingChange<QueueMode> steeringMode,
            SettingChange<QueueMode> followUpMode,
            SettingChange<Integer> maxOutputTokens,
            SettingChange<Double> temperature
    ) {
        this(defaultModel, defaultThinkingLevel, defaultTools, steeringMode, followUpMode,
                maxOutputTokens, temperature, SettingChange.keep(), SettingChange.keep(), SettingChange.keep());
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return defaultModel.operation() == SettingChange.Operation.KEEP
                && defaultThinkingLevel.operation() == SettingChange.Operation.KEEP
                && defaultTools.operation() == SettingChange.Operation.KEEP
                && steeringMode.operation() == SettingChange.Operation.KEEP
                && followUpMode.operation() == SettingChange.Operation.KEEP
                && maxOutputTokens.operation() == SettingChange.Operation.KEEP
                && temperature.operation() == SettingChange.Operation.KEEP
                && compactionEnabled.operation() == SettingChange.Operation.KEEP
                && compactionReserveTokens.operation() == SettingChange.Operation.KEEP
                && compactionKeepRecentTokens.operation() == SettingChange.Operation.KEEP;
    }

    private static SettingChange<List<CodingTool>> copyListChange(
            SettingChange<List<CodingTool>> change
    ) {
        if (change.operation() != SettingChange.Operation.SET) {
            return change;
        }
        return SettingChange.set(List.copyOf(change.value().orElseThrow()));
    }

    /** Builder exposes one operation per field, making conflicting updates impossible. */
    public static final class Builder {
        private SettingChange<ModelRef> defaultModel = SettingChange.keep();
        private SettingChange<ThinkingLevel> defaultThinkingLevel = SettingChange.keep();
        private SettingChange<List<CodingTool>> defaultTools = SettingChange.keep();
        private SettingChange<QueueMode> steeringMode = SettingChange.keep();
        private SettingChange<QueueMode> followUpMode = SettingChange.keep();
        private SettingChange<Integer> maxOutputTokens = SettingChange.keep();
        private SettingChange<Double> temperature = SettingChange.keep();
        private SettingChange<Boolean> compactionEnabled = SettingChange.keep();
        private SettingChange<Integer> compactionReserveTokens = SettingChange.keep();
        private SettingChange<Integer> compactionKeepRecentTokens = SettingChange.keep();

        public Builder setDefaultModel(ModelRef value) {
            requireUnset(defaultModel, "defaultModel");
            defaultModel = SettingChange.set(value);
            return this;
        }

        public Builder removeDefaultModel() {
            requireUnset(defaultModel, "defaultModel");
            defaultModel = SettingChange.remove();
            return this;
        }

        public Builder setDefaultThinkingLevel(ThinkingLevel value) {
            requireUnset(defaultThinkingLevel, "defaultThinkingLevel");
            defaultThinkingLevel = SettingChange.set(value);
            return this;
        }

        public Builder removeDefaultThinkingLevel() {
            requireUnset(defaultThinkingLevel, "defaultThinkingLevel");
            defaultThinkingLevel = SettingChange.remove();
            return this;
        }

        public Builder setDefaultTools(List<CodingTool> value) {
            requireUnset(defaultTools, "defaultTools");
            defaultTools = SettingChange.set(List.copyOf(value));
            return this;
        }

        public Builder removeDefaultTools() {
            requireUnset(defaultTools, "defaultTools");
            defaultTools = SettingChange.remove();
            return this;
        }

        public Builder setSteeringMode(QueueMode value) {
            requireUnset(steeringMode, "steeringMode");
            steeringMode = SettingChange.set(value);
            return this;
        }

        public Builder removeSteeringMode() {
            requireUnset(steeringMode, "steeringMode");
            steeringMode = SettingChange.remove();
            return this;
        }

        public Builder setFollowUpMode(QueueMode value) {
            requireUnset(followUpMode, "followUpMode");
            followUpMode = SettingChange.set(value);
            return this;
        }

        public Builder removeFollowUpMode() {
            requireUnset(followUpMode, "followUpMode");
            followUpMode = SettingChange.remove();
            return this;
        }

        public Builder setMaxOutputTokens(int value) {
            requireUnset(maxOutputTokens, "maxOutputTokens");
            maxOutputTokens = SettingChange.set(value);
            return this;
        }

        public Builder removeMaxOutputTokens() {
            requireUnset(maxOutputTokens, "maxOutputTokens");
            maxOutputTokens = SettingChange.remove();
            return this;
        }

        public Builder setTemperature(double value) {
            requireUnset(temperature, "temperature");
            temperature = SettingChange.set(value);
            return this;
        }

        public Builder removeTemperature() {
            requireUnset(temperature, "temperature");
            temperature = SettingChange.remove();
            return this;
        }

        public Builder setCompactionEnabled(boolean value) {
            requireUnset(compactionEnabled, "compactionEnabled");
            compactionEnabled = SettingChange.set(value);
            return this;
        }

        public Builder removeCompactionEnabled() {
            requireUnset(compactionEnabled, "compactionEnabled");
            compactionEnabled = SettingChange.remove();
            return this;
        }

        public Builder setCompactionReserveTokens(int value) {
            requireUnset(compactionReserveTokens, "compactionReserveTokens");
            compactionReserveTokens = SettingChange.set(value);
            return this;
        }

        public Builder removeCompactionReserveTokens() {
            requireUnset(compactionReserveTokens, "compactionReserveTokens");
            compactionReserveTokens = SettingChange.remove();
            return this;
        }

        public Builder setCompactionKeepRecentTokens(int value) {
            requireUnset(compactionKeepRecentTokens, "compactionKeepRecentTokens");
            compactionKeepRecentTokens = SettingChange.set(value);
            return this;
        }

        public Builder removeCompactionKeepRecentTokens() {
            requireUnset(compactionKeepRecentTokens, "compactionKeepRecentTokens");
            compactionKeepRecentTokens = SettingChange.remove();
            return this;
        }

        private static void requireUnset(SettingChange<?> current, String field) {
            if (current.operation() != SettingChange.Operation.KEEP) {
                throw new IllegalStateException(field + " already has an update");
            }
        }

        public SettingsUpdate build() {
            return new SettingsUpdate(
                    defaultModel, defaultThinkingLevel, defaultTools,
                    steeringMode, followUpMode, maxOutputTokens, temperature,
                    compactionEnabled, compactionReserveTokens, compactionKeepRecentTokens);
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
