package site.pplee.jcode.codingagent.session;

import java.time.Instant;

/** Session-tree node that changes the global display name; null clears it. */
public record SessionInfoEntry(
        String id,
        String parentId,
        Instant timestamp,
        String name
) implements SessionEntry {
    public static final String TYPE = "session_info";

    public SessionInfoEntry {
        SessionEntries.validateBase(id, parentId, timestamp);
    }

    @Override
    public String type() {
        return TYPE;
    }
}
