package site.pplee.jcode.ai.client;

import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Message;

import java.util.concurrent.CompletionStage;

/**
 * SPI for a model-call adapter: given a provider-neutral {@link ModelRequest}
 * and a read-only {@link CancellationSignal}, produce the model's final
 * {@link Message.Assistant} response.
 *
 * <p>Wave 0 contract (final-result style): the adapter aggregates any streaming
 * into one final assistant message and surfaces provider failures as a failed
 * {@link CompletionStage}; the caller converts these to an {@code ERROR}
 * assistant message. Wave 1 replaces this with a first-class
 * {@code AssistantMessageStream} so deltas, model errors, network errors and
 * active cancellation all produce a final assistant message through the same
 * stream protocol.
 *
 * <p>Adapters must not throw synchronously; encode request/model/runtime
 * failures in the returned stage.
 */
public interface ModelClient {
    /**
     * Request a model response. The returned stage completes with the final
     * {@link Message.Assistant}; on provider/network failure it completes
     * exceptionally.
     *
     * @param request     provider-neutral request (model, system prompt, standard messages, tool specs)
     * @param cancellation read-only cancellation signal; honored on a best-effort basis
     * @return a stage that completes with the final assistant message or fails exceptionally
     */
    CompletionStage<Message.Assistant> generate(ModelRequest request, CancellationSignal cancellation);
}
