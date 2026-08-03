package site.pplee.jcode.agentcore.turn;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Hook invoked after a completed turn — and after any
 * {@link PrepareNextTurn} update has been applied — to request a graceful
 * stop. {@link Decision#STOP} ends the run before the post-turn steering and
 * follow-up queues are drained, without cancelling the already-finished
 * provider stream or tools and without altering the assistant stop reason.
 * {@link Decision#CONTINUE} merely allows the normal scheduler to keep
 * deciding; it never forces another model call.
 *
 * <p>The loop waits for the returned stage; a slow hook blocks the run.
 * Failures (synchronous throw, exceptional or null stage, null decision) are
 * normalized into a terminal assistant message.
 */
@FunctionalInterface
public interface ShouldStopAfterTurn {
    /**
     * @param turn         the completed turn snapshot, post-update
     * @param cancellation the run's read-only cancellation signal
     * @return a stage completing with the stop decision
     */
    CompletionStage<Decision> shouldStopAfterTurn(
            TurnContext turn,
            CancellationSignal cancellation
    );

    /** Default hook that always continues. */
    static ShouldStopAfterTurn never() {
        return (turn, cancellation) -> CompletableFuture.completedStage(Decision.CONTINUE);
    }

    /** Whether the run should stop gracefully after the current turn. */
    enum Decision { CONTINUE, STOP }
}
