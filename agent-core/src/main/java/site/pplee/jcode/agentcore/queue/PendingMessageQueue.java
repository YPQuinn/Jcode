package site.pplee.jcode.agentcore.queue;

import site.pplee.jcode.agentcore.model.AgentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class PendingMessageQueue implements PendingMessageSource {
    private final ConcurrentLinkedQueue<AgentMessage> queue = new ConcurrentLinkedQueue<>();
    private volatile QueueMode mode;

    public PendingMessageQueue(QueueMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }

    public QueueMode mode() {
        return mode;
    }

    public void mode(QueueMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }

    public void enqueue(AgentMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        queue.add(message);
    }

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
