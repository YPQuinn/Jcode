package site.pplee.jcode.agentcore.message;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Transforms the open {@link AgentMessage} transcript into a request-local
 * view before each model call. Operates at the {@code AgentMessage} level so
 * it can prune, inject, or reorder custom product messages.
 *
 * <p><b>Call timing</b>: invoked once before every {@code ModelRequest},
 * strictly before {@link MessageProjector}. The loop waits for the returned
 * stage to complete; a slow transformer blocks the run.
 *
 * <p><b>Transcript isolation</b>: the output is a request-only view. It must
 * not be written back into {@code AgentContext} or {@code LoopState}. Messages
 * injected here appear in the model request but not in the transcript,
 * {@code LoopResult.newMessages()}, or lifecycle events.
 *
 * <p><b>Cancellation</b>: the run's read-only {@link CancellationSignal} is
 * passed so the transformer can observe cancellation. Cancellation is
 * cooperative — the loop awaits the stage regardless.
 *
 * <p><b>Output contract</b>: the returned stage, the list, and every element
 * must be non-null. The loop defensively copies and validates the result.
 * Failure (synchronous throw or exceptional stage) is normalized into a
 * terminal {@code ERROR} (or {@code ABORTED} if cancelled) assistant message;
 * the model is not called.
 *
 * <p><b>Immutability</b>: the input list is an immutable snapshot. The
 * transformer must not mutate it in place.
 */
@FunctionalInterface
public interface ContextTransformer {
    /**
     * @param messages    the current turn's immutable, ordered message snapshot
     * @param cancellation the run's read-only cancellation signal
     * @return a stage completing with the request-local message view
     */
    CompletionStage<List<AgentMessage>> transform(
            List<AgentMessage> messages,
            CancellationSignal cancellation
    );

    /** Default transformer that returns the input messages unchanged. */
    static ContextTransformer identity() {
        return (messages, cancellation) -> CompletableFuture.completedFuture(messages);
    }
}
