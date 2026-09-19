package site.pplee.jcode.codingagent.event;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Asynchronous observer for immutable coding-product event snapshots. */
@FunctionalInterface
public interface CodingAgentEventSink {
    /** Emit one event; the coding run waits for the returned stage. */
    CompletionStage<Void> emit(CodingAgentEvent event);

    /** Return a sink that immediately accepts every event. */
    static CodingAgentEventSink noop() {
        return event -> CompletableFuture.completedStage(null);
    }
}
