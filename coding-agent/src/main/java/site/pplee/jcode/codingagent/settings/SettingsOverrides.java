package site.pplee.jcode.codingagent.settings;

import site.pplee.jcode.ai.client.ModelRequestOptions;

import java.util.Objects;
import java.util.Optional;

/** Sparse SDK overrides, with an optional complete request-options replacement. */
public record SettingsOverrides(
        CodingAgentSettings settings,
        Optional<ModelRequestOptions> requestOptions
) {
    public SettingsOverrides {
        Objects.requireNonNull(settings, "settings must not be null");
        Objects.requireNonNull(requestOptions, "requestOptions must not be null");
        if (requestOptions.isPresent()
                && (settings.maxOutputTokens().isPresent() || settings.temperature().isPresent())) {
            throw new IllegalArgumentException(
                    "complete requestOptions and partial request settings are mutually exclusive");
        }
    }

    public static SettingsOverrides none() {
        return new SettingsOverrides(CodingAgentSettings.empty(), Optional.empty());
    }

    public static SettingsOverrides settings(CodingAgentSettings settings) {
        return new SettingsOverrides(settings, Optional.empty());
    }

    public static SettingsOverrides requestOptions(
            CodingAgentSettings settings,
            ModelRequestOptions requestOptions
    ) {
        return new SettingsOverrides(settings, Optional.of(requestOptions));
    }
}
