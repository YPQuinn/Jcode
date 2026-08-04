package site.pplee.jcode.ai.provider;

import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.ai.stream.AssistantMessageStreams;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Shared catalog validation, lookup, auth and dispatch logic for the
 * concrete {@link Models} implementations. Package-private: not part of the
 * public provider API surface.
 */
abstract class AbstractModels implements Models {

    /** Validate the per-provider catalog invariants for one provider. */
    static void validateProvider(ModelProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        String id = provider.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("provider id must not be blank");
        }
        if (provider.name() == null || provider.name().isBlank()) {
            throw new IllegalArgumentException("provider name must not be blank for " + id);
        }
        Set<ModelRef> seen = new HashSet<>();
        for (Model model : provider.models()) {
            if (!id.equals(model.provider())) {
                throw new IllegalArgumentException(
                        "model " + model.modelId() + " is served by " + model.provider()
                                + " but catalogued under provider " + id);
            }
            if (!seen.add(model.toRef())) {
                throw new IllegalArgumentException(
                        "duplicate model " + model.modelId() + " in provider " + id);
            }
        }
    }

    /** Validate a collection: unique provider ids plus per-provider invariants. */
    static Map<String, ModelProvider> validateCollection(List<ModelProvider> providers) {
        Objects.requireNonNull(providers, "providers must not be null");
        Map<String, ModelProvider> byId = new LinkedHashMap<>();
        for (ModelProvider provider : providers) {
            validateProvider(provider);
            String id = provider.id();
            if (byId.put(id, provider) != null) {
                throw new IllegalArgumentException("duplicate provider id: " + id);
            }
        }
        return Collections.unmodifiableMap(byId);
    }

    /** Merge every provider catalog into one immutable list. */
    static List<Model> flatten(Map<String, ModelProvider> providers) {
        return providers.values().stream().flatMap(p -> p.models().stream()).toList();
    }

    /**
     * Dispatch a request against a captured provider snapshot: unknown
     * provider and unsupported model produce synthetic {@code Start -> Error}
     * streams; otherwise the owning provider handles the request.
     */
    static AssistantMessageStream dispatch(
            Map<String, ModelProvider> snapshot,
            ModelRequest request,
            CancellationSignal cancellation
    ) {
        ModelProvider provider = snapshot.get(request.model().provider());
        if (provider == null) {
            return AssistantMessageStreams.failed(
                    StopReason.ERROR, "unknown provider: " + request.model().provider());
        }
        boolean supported;
        try {
            supported = provider.supports(request.model());
        } catch (RuntimeException e) {
            return AssistantMessageStreams.failed(StopReason.ERROR,
                    "provider " + provider.id() + " failed model-ownership check: " + e.getMessage());
        }
        if (!supported) {
            return AssistantMessageStreams.failed(StopReason.ERROR,
                    "provider " + provider.id() + " does not support model "
                            + request.model().modelId());
        }
        try {
            return provider.stream(request, cancellation);
        } catch (RuntimeException e) {
            return AssistantMessageStreams.failed(StopReason.ERROR,
                    "provider " + provider.id() + " stream failed: " + e.getMessage());
        }
    }

    @Override
    public Optional<Model> model(ModelRef ref) {
        return providers().stream()
                .flatMap(p -> p.models().stream())
                .filter(m -> m.provider().equals(ref.provider())
                        && m.api().equals(ref.api())
                        && m.modelId().equals(ref.modelId()))
                .findFirst();
    }

    @Override
    public CompletionStage<AuthCheck> checkAuth(String providerId, CancellationSignal cancellation) {
        Optional<ModelProvider> found = provider(providerId);
        if (found.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new AuthCheck(providerId, false, "unknown provider"));
        }
        ProviderAuth auth = found.get().auth();
        return CompletableFuture.completedFuture(
                new AuthCheck(providerId, auth.isConfigured(), auth.diagnostic().orElse("")));
    }

    @Override
    public CompletionStage<ModelsRefreshResult> refresh(ModelsRefreshContext context) {
        var futures = providers().stream()
                .map(p -> {
                    CompletionStage<Void> stage;
                    try {
                        stage = Objects.requireNonNull(
                                p.refreshModels(context),
                                "refreshModels returned a null stage for " + p.id());
                    } catch (RuntimeException e) {
                        return CompletableFuture.completedFuture(
                                new ModelsRefreshFailure(p.id(), String.valueOf(e)));
                    }
                    return stage.handle((v, ex) -> ex == null
                                    ? null
                                    : new ModelsRefreshFailure(p.id(), String.valueOf(ex)))
                            .toCompletableFuture();
                })
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(v -> new ModelsRefreshResult(
                        futures.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList()));
    }
}
