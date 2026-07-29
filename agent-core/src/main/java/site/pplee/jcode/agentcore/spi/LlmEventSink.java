package site.pplee.jcode.agentcore.spi;

import site.pplee.jcode.agentcore.model.Content;

/**
 * Sink for model-generation deltas (text, thinking, tool-call fragments).
 * First batch; no provider-specific event types are defined here.
 */
@FunctionalInterface
public interface LlmEventSink {
    /** Forward one incremental content fragment. */
    void onDelta(Content delta);

    /** Sink that discards all deltas. */
    static LlmEventSink noop() {
        return delta -> { };
    }
}
