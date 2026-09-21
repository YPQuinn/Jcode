package site.pplee.jcode.codingagent.session;

import java.time.Instant;
import java.util.Objects;

/** Session-tree node that changes a target entry's global label; null clears it. */
public record LabelEntry(
        String id,
        String parentId,
        Instant timestamp,
        String targetId,
        String label
) implements SessionEntry {
    public static final String TYPE = "label";

    public LabelEntry {
        SessionEntries.validateBase(id, parentId, timestamp);
        Objects.requireNonNull(targetId, "targetId must not be null");
        if (targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
    }

    @Override
    public String type() {
        return TYPE;
    }
}
