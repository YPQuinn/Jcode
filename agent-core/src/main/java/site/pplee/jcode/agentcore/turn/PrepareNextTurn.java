package site.pplee.jcode.agentcore.turn;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Hook invoked between turns, after {@code TurnCompleted} has been emitted
 * and awaited, to replace the context, model, or thinking level used by the
 * next model turn of the current run. The model client itself is never
 * replaced by this hook; callers that route across providers must provide a
 * client that can serve the requested {@link site.pplee.jcode.ai.model.ModelRef}.
 *
 * <p>The hook receives the completed turn snapshot <em>before</em> any update
 * is applied. The loop waits for the returned stage; a slow hook blocks the
 * run. Failures (synchronous throw, exceptional or null stage, null update)
 * are normalized into a terminal assistant message; the run future still
 * completes normally.
 */
@FunctionalInterface
public interface PrepareNextTurn {
    /**
     * @param turn         the completed turn snapshot, pre-update
     * @param cancellation the run's read-only cancellation signal
     * @return a stage completing with the patch for the next turn
     */
    CompletionStage<NextTurnUpdate> prepareNextTurn(
            TurnContext turn,
            CancellationSignal cancellation
    );

    /** Default hook that keeps the current context, model, and thinking level. */
    static PrepareNextTurn noop() {
        return (turn, cancellation) -> CompletableFuture.completedStage(NextTurnUpdate.keep());
    }
}
