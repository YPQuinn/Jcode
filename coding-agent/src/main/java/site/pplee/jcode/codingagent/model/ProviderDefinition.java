package site.pplee.jcode.codingagent.model;

import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.aiproviders.openai.OpenAiModelCapabilities;
import site.pplee.jcode.aiproviders.openai.OpenAiResponsesCompatibility;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Non-secret definition of one OpenAI-Responses-compatible provider connection. */
public record ProviderDefinition(
        String providerId,
        String name,
        String api,
        URI baseUrl,
        OpenAiResponsesCompatibility compatibility,
        Optional<String> apiKeyEnvironmentVariable,
        boolean allowUnlistedModels,
        List<DefinedModel> models
) {
    public ProviderDefinition {
        requireText(providerId, "providerId");
        requireText(name, "name");
        requireText(api, "api");
        validateBaseUrl(baseUrl);
        Objects.requireNonNull(compatibility, "compatibility must not be null");
        Objects.requireNonNull(
                apiKeyEnvironmentVariable, "apiKeyEnvironmentVariable must not be null");
        apiKeyEnvironmentVariable.ifPresent(value -> requireText(value, "apiKeyEnvironmentVariable"));
        models = List.copyOf(Objects.requireNonNull(models, "models must not be null"));
        var ids = new HashSet<String>();
        for (var model : models) {
            Objects.requireNonNull(model, "models must not contain null");
            if (!ids.add(model.id())) {
                throw new IllegalArgumentException("duplicate model id " + model.id());
            }
        }
    }

    public List<Model> catalog() {
        return models.stream()
                .map(model -> new Model(providerId, api, model.id(), model.name()))
                .toList();
    }

    private static void validateBaseUrl(URI baseUrl) {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        String scheme = baseUrl.getScheme();
        if (!baseUrl.isAbsolute()
                || (!("http".equalsIgnoreCase(scheme)) && !("https".equalsIgnoreCase(scheme)))
                || baseUrl.getHost() == null) {
            throw new IllegalArgumentException("baseUrl must be an absolute HTTP(S) URI");
        }
        if (baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException(
                    "baseUrl must not contain user info, a query, or a fragment");
        }
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    /** One statically advertised model and its provider/product metadata. */
    public record DefinedModel(
            String id,
            String name,
            OpenAiModelCapabilities capabilities,
            ModelProfile profile
    ) {
        public DefinedModel {
            requireText(id, "model id");
            requireText(name, "model name");
            Objects.requireNonNull(capabilities, "capabilities must not be null");
            Objects.requireNonNull(profile, "profile must not be null");
        }
    }
}
