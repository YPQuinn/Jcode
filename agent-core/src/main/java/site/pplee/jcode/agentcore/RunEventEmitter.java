package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.event.AgentEventSink;

/**
 * Per-run synchronous delivery adapter between the asynchronous
 * {@link AgentEventSink} seam and the {@code AgentLoop} control flow.
 * Awaits the {@link java.util.concurrent.CompletionStage} returned by each
 * {@link AgentEventSink#emit(AgentEvent)} before returning, so a slow sink
 * blocks the calling run or tool-execution thread.
 *
 * <p>This is the single place where "wait for event delivery" lives; the loop
 * and tool update sink delegate here instead of calling the sink directly.
 *
 * <p>Neither catches, wraps, nor retries exceptions raised by the delegate
 * sink — failures propagate via {@code .join()} exactly as before.
 */
final class RunEventEmitter {
    private final AgentEventSink delegate;

    RunEventEmitter(AgentEventSink delegate) {
        this.delegate = (delegate == null) ? AgentEventSink.noop() : delegate;
    }

    static RunEventEmitter noop() {
        return new RunEventEmitter(AgentEventSink.noop());
    }

    void emit(AgentEvent event) {
        delegate.emit(event).toCompletableFuture().join();
    }
}
