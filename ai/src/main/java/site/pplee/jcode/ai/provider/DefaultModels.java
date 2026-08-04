package site.pplee.jcode.ai.provider;

import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageStream;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable {@link Models} collection. Validates catalog invariants at
 * construction: unique provider ids, provider/model ownership, and no
 * duplicate model refs. Use {@link CopyOnWriteModels} when providers must be
 * registered or replaced after construction.
 */
public final class DefaultModels extends AbstractModels {
    private final Map<String, ModelProvider> providers;
    private final List<Model> models;

    public DefaultModels(List<ModelProvider> providers) {
        this.providers = validateCollection(providers);
        this.models = flatten(this.providers);
    }

    @Override
    public List<ModelProvider> providers() {
        return List.copyOf(providers.values());
    }

    @Override
    public Optional<ModelProvider> provider(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    @Override
    public List<Model> models() {
        return models;
    }

    @Override
    public List<Model> models(String providerId) {
        return provider(providerId).map(p -> List.copyOf(p.models())).orElse(List.of());
    }

    @Override
    public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
        return dispatch(providers, request, cancellation);
    }
}
