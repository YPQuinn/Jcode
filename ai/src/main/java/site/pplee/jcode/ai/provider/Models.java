package site.pplee.jcode.ai.provider;

import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageStream;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Explicit runtime collection of {@link ModelProvider}s: provider lookup,
 * catalog lookup, auth status, refresh, and stream dispatch. Also serves as
 * a {@link ModelClient} routing view so a collection can be handed to the
 * agent runtime as the model client.
 *
 * <p>All failures are expressed through stream terminal {@code Error}
 * events; unknown providers and unsupported models produce synthetic
 * {@code Start -> Error} streams and never throw synchronously.
 */
public interface Models extends ModelClient {
    /** Immutable snapshot of all registered providers. */
    List<ModelProvider> providers();

    /** The provider with the given id, if registered. */
    Optional<ModelProvider> provider(String id);

    /** Merged immutable snapshot of every advertised model across providers. */
    List<Model> models();

    /** Advertised models of one provider; empty when the provider is unknown. */
    List<Model> models(String providerId);

    /** Lookup a model in the advertised catalogs by its full identity. */
    Optional<Model> model(ModelRef ref);

    /** Auth status snapshot for one provider (no secrets). */
    CompletionStage<AuthCheck> checkAuth(String providerId, CancellationSignal cancellation);

    /** Refresh every provider; per-provider failures are collected, never thrown. */
    CompletionStage<ModelsRefreshResult> refresh(ModelsRefreshContext context);

    @Override
    AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation);
}
