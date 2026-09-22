package site.pplee.jcode.codingagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.provider.DefaultModels;
import site.pplee.jcode.ai.provider.ModelProvider;
import site.pplee.jcode.ai.provider.Models;
import site.pplee.jcode.aiproviders.openai.OpenAiModelCapabilities;
import site.pplee.jcode.aiproviders.openai.OpenAiProvider;
import site.pplee.jcode.aiproviders.openai.OpenAiProviderConfig;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Builds a session-owned {@link DefaultModels} from definitions with resolved credentials. */
public final class ModelRuntimeAssembler {
    private ModelRuntimeAssembler() {
    }

    public static ModelRuntime borrowed(
            Models models,
            Map<ModelRef, ModelProfile> profiles
    ) {
        return new ModelRuntime(models, profiles, List.of(), Optional.empty());
    }

    public static ModelRuntime owned(
            List<ProviderDefinition> definitions,
            CredentialResolver.Resolution credentialResolution
    ) {
        return owned(definitions, credentialResolution, () -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    static ModelRuntime owned(
            List<ProviderDefinition> definitions,
            CredentialResolver.Resolution credentialResolution,
            Supplier<? extends HttpClient> httpClientFactory
    ) {
        Objects.requireNonNull(definitions, "definitions must not be null");
        Objects.requireNonNull(
                credentialResolution, "credentialResolution must not be null");
        Objects.requireNonNull(httpClientFactory, "httpClientFactory must not be null");
        var providers = new ArrayList<ModelProvider>();
        var ownedResources = new ArrayList<AutoCloseable>();
        var profiles = new LinkedHashMap<ModelRef, ModelProfile>();
        try {
            for (var definition : definitions) {
                var resolved = credentialResolution.credentials().get(definition.providerId());
                if (resolved == null) {
                    continue;
                }
                var capabilities = new LinkedHashMap<String, OpenAiModelCapabilities>();
                for (var model : definition.models()) {
                    var ref = new ModelRef(
                            definition.providerId(), definition.api(), model.id());
                    profiles.put(ref, model.profile());
                    capabilities.put(model.id(), model.capabilities());
                }
                var config = OpenAiProviderConfig.builder()
                        .providerId(definition.providerId())
                        .providerName(definition.name())
                        .api(definition.api())
                        .baseUrl(definition.baseUrl())
                        .compatibility(definition.compatibility())
                        .allowUnlistedModels(definition.allowUnlistedModels())
                        .models(definition.catalog())
                        .capabilities(capabilities)
                        .credentials(resolved.credentials())
                        .build();
                var httpClient = Objects.requireNonNull(
                        httpClientFactory.get(), "httpClientFactory must not return null");
                ownedResources.add(httpClient);
                var provider = new OpenAiProvider(config, httpClient, new ObjectMapper());
                providers.add(provider);
                ownedResources.add(provider);
            }
            var models = new DefaultModels(providers);
            return new ModelRuntime(
                    models,
                    profiles,
                    credentialResolution.diagnostics(),
                    Optional.of(new ProviderResources(ownedResources)));
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(ownedResources, failure);
            throw failure;
        }
    }

    private static void closeAfterFailure(List<AutoCloseable> resources, Throwable failure) {
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private static final class ProviderResources implements AutoCloseable {
        private final List<AutoCloseable> resources;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ProviderResources(List<AutoCloseable> resources) {
            this.resources = List.copyOf(resources);
        }

        @Override
        public void close() throws Exception {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Exception failure = null;
            for (int index = resources.size() - 1; index >= 0; index--) {
                try {
                    resources.get(index).close();
                } catch (Exception closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
