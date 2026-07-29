package site.pplee.jcode.agentcore.queue;

import site.pplee.jcode.agentcore.model.AgentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * {@link PendingMessageSource} backed by a {@link ConcurrentLinkedQueue}. The
 * {@link QueueMode} is {@code volatile} and may be changed at runtime;
 * {@code enqueue} never blocks, {@code drain} returns an immutable snapshot.
 */
public final class PendingMessageQueue implements PendingMessageSource {
    private final ConcurrentLinkedQueue<AgentMessage> queue = new ConcurrentLinkedQueue<>();
    private volatile QueueMode mode;

    public PendingMessageQueue(QueueMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }

    /** Current drain mode. */
    public QueueMode mode() {
        return mode;
    }

    /** Change the drain mode at runtime. */
    public void mode(QueueMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }

    /** Append a message; never blocks. */
    public void enqueue(AgentMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        queue.add(message);
    }

    /**
     * Drain per the mode: {@link QueueMode#ALL} takes everything, {@link
     * QueueMode#ONE_AT_A_TIME} takes the oldest only. Returns an immutable list.
     */
    @Override
    public List<AgentMessage> drain() {
        QueueMode current = this.mode;
        if (current == QueueMode.ALL) {
            List<AgentMessage> snapshot = new ArrayList<>();
            AgentMessage m;
            while ((m = queue.poll()) != null) {
                snapshot.add(m);
            }
            return List.copyOf(snapshot);
        }
        AgentMessage head = queue.poll();
        return head == null ? List.of() : List.of(head);
    }
}
