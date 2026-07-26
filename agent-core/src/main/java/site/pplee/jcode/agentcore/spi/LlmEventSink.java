package site.pplee.jcode.agentcore.spi;

import site.pplee.jcode.agentcore.model.Content;

@FunctionalInterface
public interface LlmEventSink {
    void onDelta(Content delta);

    static LlmEventSink noop() {
        return delta -> { };
    }
}
