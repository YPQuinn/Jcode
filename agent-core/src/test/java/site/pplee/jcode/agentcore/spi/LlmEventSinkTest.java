package site.pplee.jcode.agentcore.spi;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.model.Content;

class LlmEventSinkTest {
    @Test
    void noopDoesNotThrowOnRealDelta() {
        var sink = LlmEventSink.noop();
        sink.onDelta(new Content.Text("chunk"));
    }

    @Test
    void noopToleratesNullDelta() {
        var sink = LlmEventSink.noop();
        // noop ignores its argument entirely; null must not throw.
        sink.onDelta(null);
    }
}
