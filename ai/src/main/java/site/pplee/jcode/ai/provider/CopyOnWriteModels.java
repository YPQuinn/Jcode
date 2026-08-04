package site.pplee.jcode.ai.provider;

import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageStream;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mutable {@link Models} implementation with copy-on-write semantics.
 * Mutations are serialized and publish a fresh immutable provider map; reads
 * (including {@link #stream}) capture a call-start snapshot so an active
 * stream is never affected by later registration changes.
 */
public final class CopyOnWriteModels extends AbstractModels implements MutableModels {
    private volatile Map<String, ModelProvider> providers = Map.of();

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
        return flatten(providers);
    }

    @Override
    public List<Model> models(String providerId) {
        return provider(providerId).map(p -> List.copyOf(p.models())).orElse(List.of());
    }

    @Override
    public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
        return dispatch(providers, request, cancellation);
    }

    @Override
    public synchronized void setProvider(ModelProvider provider) {
        validateProvider(provider);
        Map<String, ModelProvider> next = new LinkedHashMap<>(providers);
        next.put(provider.id(), provider);
        providers = Collections.unmodifiableMap(next);
    }

    @Override
    public synchronized void deleteProvider(String id) {
        if (!providers.containsKey(id)) {
            return;
        }
        Map<String, ModelProvider> next = new LinkedHashMap<>(providers);
        next.remove(id);
        providers = Collections.unmodifiableMap(next);
    }

    @Override
    public synchronized void clearProviders() {
        providers = Map.of();
    }
}
