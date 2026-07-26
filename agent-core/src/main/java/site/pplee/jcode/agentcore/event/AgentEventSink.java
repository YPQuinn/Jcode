package site.pplee.jcode.agentcore.event;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AgentEventSink {
    CompletionStage<Void> emit(AgentEvent event);

    static AgentEventSink noop() {
        return event -> CompletableFuture.completedStage(null);
    }
}
