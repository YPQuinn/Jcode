package site.pplee.jcode.agentcore.queue;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingMessageQueueTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-02T00:00:00Z");

    private static AgentMessage user(String text, Instant t) {
        return StandardAgentMessage.of(new Message.User(List.of(new Content.Text(text)), t));
    }

    @Test
    void drainEmptyQueueReturnsEmptyList() {
        var queue = new PendingMessageQueue(QueueMode.ALL);
        assertTrue(queue.drain().isEmpty());
    }

    @Test
    void enqueueThenDrainReturnsMessage() {
        var queue = new PendingMessageQueue(QueueMode.ALL);
        var msg = user("a", T1);
        queue.enqueue(msg);

        assertEquals(List.of(msg), queue.drain());
    }

    @Test
    void drainRemovesMessages() {
        var queue = new PendingMessageQueue(QueueMode.ALL);
        queue.enqueue(user("a", T1));
        queue.drain();

        assertTrue(queue.drain().isEmpty());
    }

    @Test
    void drainAllPreservesFifoOrder() {
        var queue = new PendingMessageQueue(QueueMode.ALL);
        var a = user("a", T1);
        var b = user("b", T2);
        queue.enqueue(a);
        queue.enqueue(b);

        assertEquals(List.of(a, b), queue.drain());
    }

    @Test
    void enqueueRejectsNull() {
        var queue = new PendingMessageQueue(QueueMode.ALL);
        assertThrows(NullPointerException.class, () -> queue.enqueue(null));
    }

    @Test
    void drainAllReturnsImmutableList() {
        var queue = new PendingMessageQueue(QueueMode.ALL);
        queue.enqueue(user("a", T1));

        var drained = queue.drain();
        assertThrows(UnsupportedOperationException.class,
                () -> drained.add(user("b", T2)));
    }

    @Test
    void drainOneAtATimeReturnsOnlyOldest() {
        var queue = new PendingMessageQueue(QueueMode.ONE_AT_A_TIME);
        var a = user("a", T1);
        var b = user("b", T2);
        queue.enqueue(a);
        queue.enqueue(b);

        assertEquals(List.of(a), queue.drain());
        assertEquals(List.of(b), queue.drain());
        assertTrue(queue.drain().isEmpty());
    }

    @Test
    void drainOneAtATimeOnEmptyReturnsEmptyList() {
        var queue = new PendingMessageQueue(QueueMode.ONE_AT_A_TIME);
        assertTrue(queue.drain().isEmpty());
    }

    @Test
    void modeSwitchesDrainBehaviorAtRuntime() {
        var queue = new PendingMessageQueue(QueueMode.ONE_AT_A_TIME);
        queue.enqueue(user("a", T1));
        queue.enqueue(user("b", T2));
        queue.enqueue(user("c", T1));

        // ONE_AT_A_TIME: drains oldest only
        assertEquals(1, queue.drain().size());

        queue.mode(QueueMode.ALL);
        // ALL: drains everything left in one go
        assertEquals(2, queue.drain().size());
        assertTrue(queue.drain().isEmpty());
    }

    @Test
    void modeAccessorReflectsCurrentMode() {
        var queue = new PendingMessageQueue(QueueMode.ALL);
        assertEquals(QueueMode.ALL, queue.mode());

        queue.mode(QueueMode.ONE_AT_A_TIME);
        assertEquals(QueueMode.ONE_AT_A_TIME, queue.mode());
    }

    @Test
    void constructorRejectsNullMode() {
        assertThrows(NullPointerException.class, () -> new PendingMessageQueue(null));
    }

    @Test
    void modeSetterRejectsNull() {
        var queue = new PendingMessageQueue(QueueMode.ALL);
        assertThrows(NullPointerException.class, () -> queue.mode(null));
    }
}
