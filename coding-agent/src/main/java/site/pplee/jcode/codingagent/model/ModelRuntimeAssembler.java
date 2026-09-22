package site.pplee.jcode.codingagent.model;

import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.provider.DefaultModels;
import site.pplee.jcode.ai.provider.ModelProvider;
import site.pplee.jcode.ai.provider.Models;
import site.pplee.jcode.aiproviders.openai.OpenAiModelCapabilities;
import site.pplee.jcode.aiproviders.openai.OpenAiProvider;
import site.pplee.jcode.aiproviders.openai.OpenAiProviderConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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
        Objects.requireNonNull(definitions, "definitions must not be null");
        Objects.requireNonNull(
                credentialResolution, "credentialResolution must not be null");
        var providers = new ArrayList<ModelProvider>();
        var ownedProviders = new ArrayList<AutoCloseable>();
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
                var provider = new OpenAiProvider(config);
                providers.add(provider);
                ownedProviders.add(provider);
            }
            var models = new DefaultModels(providers);
            return new ModelRuntime(
                    models,
                    profiles,
                    credentialResolution.diagnostics(),
                    Optional.of(new ProviderResources(ownedProviders)));
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(ownedProviders, failure);
            throw failure;
        }
    }

    private static void closeAfterFailure(List<AutoCloseable> providers, Throwable failure) {
        for (int index = providers.size() - 1; index >= 0; index--) {
            try {
                providers.get(index).close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private static final class ProviderResources implements AutoCloseable {
        private final List<AutoCloseable> providers;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ProviderResources(List<AutoCloseable> providers) {
            this.providers = List.copyOf(providers);
        }

        @Override
        public void close() throws Exception {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Exception failure = null;
            for (int index = providers.size() - 1; index >= 0; index--) {
                try {
                    providers.get(index).close();
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
