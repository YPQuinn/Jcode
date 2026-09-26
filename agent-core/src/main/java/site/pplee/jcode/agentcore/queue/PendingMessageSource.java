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

    /** Claim messages while retaining opaque identities when the source has them. */
    default List<PendingMessage> drainPending() {
        return drain().stream().map(PendingMessage::new).toList();
    }

    /** Grant application of a claimed message immediately before context append. */
    default boolean beginApply(String inputId) {
        return true;
    }

    /** Report that event delivery failed before the message could enter context. */
    default void applyNotStarted(String inputId, Throwable failure) {
    }

    /** Report that an attempted context or completed-message delivery failed. */
    default void applyFailed(String inputId, Throwable failure) {
    }
}
