package site.pplee.jcode.codingagent.settings;

import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.codingagent.tool.CodingTool;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One sparse settings layer. Empty optionals mean that the layer has no
 * opinion; an explicitly empty tool list remains a present value.
 */
public record CodingAgentSettings(
        Optional<ModelRef> defaultModel,
        Optional<ThinkingLevel> defaultThinkingLevel,
        Optional<List<CodingTool>> defaultTools,
        Optional<QueueMode> steeringMode,
        Optional<QueueMode> followUpMode,
        Optional<Integer> maxOutputTokens,
        Optional<Double> temperature
) {
    public CodingAgentSettings {
        Objects.requireNonNull(defaultModel, "defaultModel must not be null");
        Objects.requireNonNull(defaultThinkingLevel, "defaultThinkingLevel must not be null");
        Objects.requireNonNull(defaultTools, "defaultTools must not be null");
        Objects.requireNonNull(steeringMode, "steeringMode must not be null");
        Objects.requireNonNull(followUpMode, "followUpMode must not be null");
        Objects.requireNonNull(maxOutputTokens, "maxOutputTokens must not be null");
        Objects.requireNonNull(temperature, "temperature must not be null");
        defaultTools = defaultTools.map(List::copyOf);
        if (defaultTools.stream().flatMap(List::stream).anyMatch(Objects::isNull)) {
            throw new NullPointerException("defaultTools must not contain null");
        }
        defaultTools.ifPresent(tools -> {
            if (new HashSet<>(tools).size() != tools.size()) {
                throw new IllegalArgumentException("defaultTools must not contain duplicates");
            }
        });
        maxOutputTokens.ifPresent(value -> {
            if (value <= 0) {
                throw new IllegalArgumentException("maxOutputTokens must be positive");
            }
        });
        temperature.ifPresent(value -> {
            if (!Double.isFinite(value) || value < 0.0d) {
                throw new IllegalArgumentException("temperature must be finite and non-negative");
            }
        });
    }

    public static CodingAgentSettings empty() {
        return new CodingAgentSettings(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable construction aid; built settings remain immutable. */
    public static final class Builder {
        private ModelRef defaultModel;
        private ThinkingLevel defaultThinkingLevel;
        private List<CodingTool> defaultTools;
        private QueueMode steeringMode;
        private QueueMode followUpMode;
        private Integer maxOutputTokens;
        private Double temperature;

        public Builder defaultModel(ModelRef value) {
            defaultModel = Objects.requireNonNull(value, "defaultModel must not be null");
            return this;
        }

        public Builder defaultThinkingLevel(ThinkingLevel value) {
            defaultThinkingLevel = Objects.requireNonNull(
                    value, "defaultThinkingLevel must not be null");
            return this;
        }

        public Builder defaultTools(List<CodingTool> value) {
            defaultTools = List.copyOf(Objects.requireNonNull(value, "defaultTools must not be null"));
            return this;
        }

        public Builder steeringMode(QueueMode value) {
            steeringMode = Objects.requireNonNull(value, "steeringMode must not be null");
            return this;
        }

        public Builder followUpMode(QueueMode value) {
            followUpMode = Objects.requireNonNull(value, "followUpMode must not be null");
            return this;
        }

        public Builder maxOutputTokens(int value) {
            maxOutputTokens = value;
            return this;
        }

        public Builder temperature(double value) {
            temperature = value;
            return this;
        }

        public CodingAgentSettings build() {
            return new CodingAgentSettings(
                    Optional.ofNullable(defaultModel),
                    Optional.ofNullable(defaultThinkingLevel),
                    Optional.ofNullable(defaultTools),
                    Optional.ofNullable(steeringMode),
                    Optional.ofNullable(followUpMode),
                    Optional.ofNullable(maxOutputTokens),
                    Optional.ofNullable(temperature));
        }
    }
}
