package site.pplee.jcode.codingagent.session;

import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.codingagent.internal.SnapshotMapper;

import java.time.Instant;
import java.util.Objects;

/** Session-tree node containing one completed standard transcript message. */
public record SessionMessageEntry(
        String id,
        String parentId,
        Instant timestamp,
        StandardAgentMessage message
) implements SessionEntry {
    public static final String TYPE = "message";

    public SessionMessageEntry {
        SessionEntries.validateBase(id, parentId, timestamp);
        message = copy(Objects.requireNonNull(message, "message must not be null"));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StandardAgentMessage message() {
        return copy(message);
    }

    private static StandardAgentMessage copy(StandardAgentMessage message) {
        return (StandardAgentMessage) SnapshotMapper.agentMessage(message);
    }

    @Override
    public String toString() {
        return "SessionMessageEntry[id=" + id + ", parentId=" + parentId
                + ", timestamp=" + timestamp + ", message=present]";
    }
}
