package site.pplee.jcode.ai.stream;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link AssistantMessageStreams}: every synthetic failure
 * stream must follow the well-formed {@code Start -> Error} lifecycle and
 * complete its result stage normally.
 */
class AssistantMessageStreamsTest {

    @Test
    void failedProducesStartThenErrorLifecycle() throws Exception {
        var stream = AssistantMessageStreams.failed(StopReason.ERROR, "boom");

        var start = assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertEquals(StopReason.STOP, start.partial().stopReason());
        assertTrue(start.partial().content().isEmpty());

        var error = assertInstanceOf(AssistantMessageEvent.Error.class, stream.take());
        assertEquals(StopReason.ERROR, error.reason());
        assertEquals("boom", error.error().errorMessage());
        assertTrue(error.error().content().isEmpty());

        assertNull(stream.take());
    }

    @Test
    void failedCarriesPartialContent() throws Exception {
        var content = List.<Content>of(new Content.Text("partial"));
        var stream = AssistantMessageStreams.failed(StopReason.ABORTED, "cancelled", content);

        var start = assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertEquals("partial", ((Content.Text) start.partial().content().get(0)).text());

        var error = assertInstanceOf(AssistantMessageEvent.Error.class, stream.take());
        assertEquals(StopReason.ABORTED, error.reason());
        assertEquals("partial", ((Content.Text) error.error().content().get(0)).text());
    }

    @Test
    void failedRejectsNonTerminalReason() {
        for (var reason : List.of(StopReason.STOP, StopReason.TOOL_CALL, StopReason.LENGTH)) {
            assertThrows(IllegalArgumentException.class, () -> AssistantMessageStreams.failed(reason, "x"));
        }
    }

    @Test
    void resultStageCompletesNormallyWithFailureMessage() {
        var stream = AssistantMessageStreams.failed(StopReason.ERROR, "boom");
        var result = stream.result();
        assertEquals(StopReason.ERROR, result.stopReason());
        assertEquals("boom", result.errorMessage());
    }

    @Test
    void latePushAfterFailedIsIgnored() throws Exception {
        var stream = AssistantMessageStreams.failed(StopReason.ERROR, "boom");
        stream.push(new AssistantMessageEvent.TextDelta(0, "late",
                new Message.Assistant(List.of(), StopReason.STOP, null, Usage.zero(), Instant.now())));
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertInstanceOf(AssistantMessageEvent.Error.class, stream.take());
        assertNull(stream.take());
    }
}
