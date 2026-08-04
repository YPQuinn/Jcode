package site.pplee.jcode.ai.provider;

import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageStream;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A provider runtime unit: the concrete representation of one model provider.
 * Owns provider identity, optional base URL, auth status, the advertised
 * model catalog, model-ownership checks, and stream behavior. Providers are
 * composed explicitly into a {@link Models} collection; there is no global
 * registry and no classpath auto-registration.
 *
 * <p>A provider is not an adapter: the low-level API-dialect adapter is an
 * implementation detail inside the provider. Stream failures (unknown model,
 * missing auth, network errors) are expressed through the returned
 * {@link AssistantMessageStream} terminal {@code Error} events, never by
 * throwing synchronously.
 */
public interface ModelProvider {
    /** Stable provider id (must match {@link Model#provider()} of catalogued models). */
    String id();

    /** Human-readable provider name. */
    String name();

    /** Base URL when the provider speaks HTTP; empty for embedded/non-HTTP providers. */
    Optional<URI> baseUrl();

    /** Auth status diagnostic; never exposes resolved secrets. */
    ProviderAuth auth();

    /** Advertised model catalog. Empty means "no advertised catalog", not a wildcard. */
    List<Model> models();

    /** Whether this provider accepts the given model reference. */
    boolean supports(ModelRef ref);

    /**
     * Refresh the advertised catalog. Default: static catalog, no network
     * refresh. Failures must be reported through the caller's
     * {@link ModelsRefreshResult} rather than an exceptional future.
     */
    default CompletionStage<Void> refreshModels(ModelsRefreshContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Start a model response stream. Must not throw synchronously for
     * provider, network, or model failures; those are encoded as terminal
     * {@code Error} events on the returned stream.
     */
    AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation);
}
