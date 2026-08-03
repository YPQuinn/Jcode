package site.pplee.jcode.ai.client;

import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.stream.AssistantMessageStream;

/**
 * SPI for a model-call adapter: given a provider-neutral
 * {@link ModelRequest} and a read-only {@link CancellationSignal}, produce an
 * {@link AssistantMessageStream} that delivers streaming deltas and a final
 * {@link site.pplee.jcode.ai.message.Message.Assistant}.
 *
 * <p>Adapters must not throw synchronously. Request, model, and runtime
 * failures (including active cancellation) are encoded in the returned stream
 * via {@link site.pplee.jcode.ai.stream.AssistantMessageEvent.Error} events,
 * producing a final assistant message with
 * {@link site.pplee.jcode.ai.message.StopReason#ERROR} or
 * {@link site.pplee.jcode.ai.message.StopReason#ABORTED}.
 */
public interface ModelClient {
    /**
     * Start a model response stream. The returned stream is ready for
     * immediate consumption via {@link AssistantMessageStream#take()}.
     *
     * @param request      provider-neutral request (model, system prompt, standard messages,
 *                     tool specs, thinking level)
     * @param cancellation read-only cancellation signal; honored on a best-effort basis
     * @return a stream of assistant message events terminating in Done or Error
     */
    AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation);
}
