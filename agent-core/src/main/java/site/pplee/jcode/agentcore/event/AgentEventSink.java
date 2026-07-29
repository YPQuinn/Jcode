package site.pplee.jcode.agentcore.event;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Observer of {@link AgentEvent}s. The loop awaits each {@link #emit} before
 * proceeding, so a slow sink blocks the run.
 */
@FunctionalInterface
public interface AgentEventSink {
    /** Notify the observer; the stage completes when the sink is done. */
    CompletionStage<Void> emit(AgentEvent event);

    /** Sink that completes immediately and records nothing. */
    static AgentEventSink noop() {
        return event -> CompletableFuture.completedStage(null);
    }
}
