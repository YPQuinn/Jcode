package site.pplee.jcode.agentcore.queue;

import site.pplee.jcode.agentcore.message.AgentMessage;

import java.util.List;

/**
 * Source of pending messages drained by the loop. Non-blocking; returns an
 * immutable snapshot.
 */
public interface PendingMessageSource {
    /** Remove and return pending messages per the queue mode. */
    List<AgentMessage> drain();
}
