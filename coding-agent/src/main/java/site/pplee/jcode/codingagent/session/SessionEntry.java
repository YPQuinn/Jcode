package site.pplee.jcode.codingagent.session;

import java.time.Instant;

/**
 * One append-only node in a session tree. A {@code null} parent identifies a
 * root; non-null parents must refer to an earlier entry in the same session.
 */
public sealed interface SessionEntry permits SessionMessageEntry, ModelChangeEntry,
        ThinkingLevelChangeEntry, SessionInfoEntry, LabelEntry {

    /** Stable serialized discriminator. */
    String type();

    /** Session-local entry identifier. */
    String id();

    /** Parent entry identifier, or {@code null} for a root. */
    String parentId();

    /** Entry creation time. */
    Instant timestamp();
}
