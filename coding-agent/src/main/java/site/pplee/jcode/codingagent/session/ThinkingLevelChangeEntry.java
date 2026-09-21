package site.pplee.jcode.codingagent.session;

import site.pplee.jcode.ai.model.ThinkingLevel;

import java.time.Instant;
import java.util.Objects;

/** Session-tree node recording the requested thinking level. */
public record ThinkingLevelChangeEntry(
        String id,
        String parentId,
        Instant timestamp,
        ThinkingLevel thinkingLevel
) implements SessionEntry {
    public static final String TYPE = "thinking_level_change";

    public ThinkingLevelChangeEntry {
        SessionEntries.validateBase(id, parentId, timestamp);
        Objects.requireNonNull(thinkingLevel, "thinkingLevel must not be null");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
