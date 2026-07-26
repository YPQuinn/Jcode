package site.pplee.jcode.agentcore.support;

import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.event.AgentEventSink;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only {@link AgentEventSink} that records every emitted event in emit
 * order. {@code emit} returns an already-completed stage so the loop's
 * per-event await is non-blocking, while event order is still enforced by the
 * loop awaiting each emit before the next.
 */
public final class RecordingEventSink implements AgentEventSink {
    private final List<AgentEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public CompletionStage<Void> emit(AgentEvent event) {
        events.add(event);
        return CompletableFuture.completedStage(null);
    }

    public List<AgentEvent> events() {
        return List.copyOf(events);
    }
}
