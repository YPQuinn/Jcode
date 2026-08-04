package site.pplee.jcode.aiproviders.openai;

import site.pplee.jcode.ai.model.Model;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit configuration for {@link OpenAiProvider}. Credentials are held
 * through the redacted {@link OpenAiCredentials} holder; {@link #toString()}
 * never reveals the API key. All values are fixed at construction; the static
 * {@link #responses(OpenAiCredentials, List)} factory builds the default
 * Responses-API configuration and {@link #builder()} allows customization.
 */
public final class OpenAiProviderConfig {
    private final URI baseUrl;
    private final OpenAiCredentials credentials;
    private final Optional<String> organization;
    private final Optional<String> project;
    private final Duration requestTimeout;
    private final String providerId;
    private final String providerName;
    private final String api;
    private final List<Model> models;
    private final Map<String, OpenAiModelCapabilities> capabilities;
    private final boolean allowUnlistedModels;

    private OpenAiProviderConfig(Builder builder) {
        this.baseUrl = Objects.requireNonNull(builder.baseUrl, "baseUrl must not be null");
        this.credentials = Objects.requireNonNull(builder.credentials, "credentials must not be null");
        this.organization = Objects.requireNonNull(builder.organization, "organization must not be null");
        this.project = Objects.requireNonNull(builder.project, "project must not be null");
        this.requestTimeout = Objects.requireNonNull(builder.requestTimeout, "requestTimeout must not be null");
        this.providerId = Objects.requireNonNull(builder.providerId, "providerId must not be null");
        this.providerName = Objects.requireNonNull(builder.providerName, "providerName must not be null");
        this.api = Objects.requireNonNull(builder.api, "api must not be null");
        this.models = List.copyOf(Objects.requireNonNull(builder.models, "models must not be null"));
        this.capabilities = Map.copyOf(Objects.requireNonNull(builder.capabilities, "capabilities must not be null"));
        this.allowUnlistedModels = builder.allowUnlistedModels;
        validateCatalog();
    }

    /**
     * Every catalog entry must belong to this provider's identity: the model's
     * provider and api dimensions must match the configured {@code providerId}
     * and {@code api}. This keeps {@link OpenAiProvider#supports} safe when it
     * matches catalog entries by full model reference.
     */
    private void validateCatalog() {
        for (Model model : models) {
            if (!providerId.equals(model.provider())) {
                throw new IllegalArgumentException(
                        "model " + model.modelId() + " is served by provider " + model.provider()
                                + " but configured for provider " + providerId);
            }
            if (!api.equals(model.api())) {
                throw new IllegalArgumentException(
                        "model " + model.modelId() + " uses api " + model.api()
                                + " but provider is configured for api " + api);
            }
        }
    }

    /** Default Responses-API configuration with the given credentials and model catalog. */
    public static OpenAiProviderConfig responses(OpenAiCredentials credentials, List<Model> models) {
        return builder().credentials(credentials).models(models).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public URI baseUrl() {
        return baseUrl;
    }

    public OpenAiCredentials credentials() {
        return credentials;
    }

    public Optional<String> organization() {
        return organization;
    }

    public Optional<String> project() {
        return project;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public String providerId() {
        return providerId;
    }

    public String providerName() {
        return providerName;
    }

    public String api() {
        return api;
    }

    public List<Model> models() {
        return models;
    }

    /** Per-model-id capability metadata; absent ids have no capabilities. */
    public Map<String, OpenAiModelCapabilities> capabilities() {
        return capabilities;
    }

    /** True when {@code supports} accepts provider/api matches outside the catalog. */
    public boolean allowUnlistedModels() {
        return allowUnlistedModels;
    }

    @Override
    public String toString() {
        return "OpenAiProviderConfig[providerId=" + providerId + ", providerName=" + providerName
                + ", baseUrl=" + baseUrl + ", api=" + api + ", models=" + models.size()
                + ", credentials=" + credentials + ", allowUnlistedModels=" + allowUnlistedModels + "]";
    }

    public static final class Builder {
        private URI baseUrl = URI.create("https://api.openai.com/v1");
        private OpenAiCredentials credentials;
        private Optional<String> organization = Optional.empty();
        private Optional<String> project = Optional.empty();
        private Duration requestTimeout = Duration.ofSeconds(60);
        private String providerId = "openai";
        private String providerName = "OpenAI";
        private String api = "openai-responses";
        private List<Model> models = List.of();
        private Map<String, OpenAiModelCapabilities> capabilities = Map.of();
        private boolean allowUnlistedModels;

        public Builder baseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder credentials(OpenAiCredentials credentials) {
            this.credentials = credentials;
            return this;
        }

        public Builder organization(String organization) {
            this.organization = Optional.of(organization);
            return this;
        }

        public Builder project(String project) {
            this.project = Optional.of(project);
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder providerId(String providerId) {
            this.providerId = providerId;
            return this;
        }

        public Builder providerName(String providerName) {
            this.providerName = providerName;
            return this;
        }

        public Builder api(String api) {
            this.api = api;
            return this;
        }

        public Builder models(List<Model> models) {
            this.models = models;
            return this;
        }

        public Builder capabilities(Map<String, OpenAiModelCapabilities> capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public Builder allowUnlistedModels(boolean allowUnlistedModels) {
            this.allowUnlistedModels = allowUnlistedModels;
            return this;
        }

        public OpenAiProviderConfig build() {
            return new OpenAiProviderConfig(this);
        }
    }
}
