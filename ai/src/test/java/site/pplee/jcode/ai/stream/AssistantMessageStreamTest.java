package site.pplee.jcode.ai.stream;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssistantMessageStreamTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");

    private static Message.Assistant assistant(String text) {
        return Message.Assistant.of(List.of(new Content.Text(text)), StopReason.STOP, T1);
    }

    @Test
    void pushAndTakeDeliverEventsInOrder() throws InterruptedException {
        var stream = new AssistantMessageStream();
        var msg = assistant("hi");
        stream.push(new AssistantMessageEvent.Start(msg));
        stream.push(new AssistantMessageEvent.Done(StopReason.STOP, msg));

        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertInstanceOf(AssistantMessageEvent.Done.class, stream.take());
        assertNull(stream.take(), "stream done after terminal event");
    }

    @Test
    void resultCompletesAfterDone() {
        var stream = new AssistantMessageStream();
        var msg = assistant("hi");
        stream.push(new AssistantMessageEvent.Start(msg));
        stream.push(new AssistantMessageEvent.Done(StopReason.STOP, msg));

        assertTrue(stream.isDone());
        assertEquals(msg, stream.result());
    }

    @Test
    void resultCompletesAfterError() {
        var stream = new AssistantMessageStream();
        var err = new Message.Assistant(List.of(),
                StopReason.ERROR, "boom", site.pplee.jcode.ai.message.Usage.zero(), T1);
        stream.push(new AssistantMessageEvent.Start(err));
        stream.push(new AssistantMessageEvent.Error(StopReason.ERROR, err));

        assertTrue(stream.isDone());
        assertEquals(err, stream.result());
    }

    @Test
    void pushAfterTerminalIsSilentlyIgnored() throws InterruptedException {
        var stream = new AssistantMessageStream();
        var msg = assistant("hi");
        stream.push(new AssistantMessageEvent.Done(StopReason.STOP, msg));
        stream.push(new AssistantMessageEvent.Start(msg));

        assertInstanceOf(AssistantMessageEvent.Done.class, stream.take());
        assertNull(stream.take());
    }

    @Test
    void doneRejectsTerminalFailureReason() {
        var msg = assistant("hi");
        assertThrows(IllegalArgumentException.class,
                () -> new AssistantMessageEvent.Done(StopReason.ERROR, msg));
        assertThrows(IllegalArgumentException.class,
                () -> new AssistantMessageEvent.Done(StopReason.ABORTED, msg));
    }

    @Test
    void errorRejectsNonTerminalFailureReason() {
        var err = new Message.Assistant(List.of(),
                StopReason.ERROR, "boom", site.pplee.jcode.ai.message.Usage.zero(), T1);
        assertThrows(IllegalArgumentException.class,
                () -> new AssistantMessageEvent.Error(StopReason.STOP, err));
        assertThrows(IllegalArgumentException.class,
                () -> new AssistantMessageEvent.Error(StopReason.TOOL_CALL, err));
    }

    @Test
    void partialReturnsAccumulatedMessage() {
        var msg = assistant("hi");
        assertEquals(msg, new AssistantMessageEvent.Start(msg).partial());
        assertEquals(msg, new AssistantMessageEvent.TextStart(0, msg).partial());
        assertEquals(msg, new AssistantMessageEvent.Done(StopReason.STOP, msg).partial());

        var err = new Message.Assistant(List.of(),
                StopReason.ERROR, "boom", site.pplee.jcode.ai.message.Usage.zero(), T1);
        assertEquals(err, new AssistantMessageEvent.Error(StopReason.ERROR, err).partial());
    }

    @Test
    void textDeltaAndThinkingDeltaRejectNullDelta() {
        var msg = assistant("hi");
        assertThrows(NullPointerException.class,
                () -> new AssistantMessageEvent.TextDelta(0, null, msg));
        assertThrows(NullPointerException.class,
                () -> new AssistantMessageEvent.ThinkingDelta(0, null, msg));
        assertThrows(NullPointerException.class,
                () -> new AssistantMessageEvent.ToolCallDelta(0, null, msg));
    }
}
