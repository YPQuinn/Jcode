package site.pplee.jcode.codingagent.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Structural validation and defensive copying shared by session infrastructure. */
public final class SessionEntries {
    private SessionEntries() {
    }

    /** Validate fields common to every entry. */
    public static void validateBase(String id, String parentId, Instant timestamp) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (parentId != null && parentId.isBlank()) {
            throw new IllegalArgumentException("parentId must not be blank");
        }
        if (id.equals(parentId)) {
            throw new IllegalArgumentException("an entry cannot be its own parent");
        }
    }

    /** Validate one append against entries that were already accepted. */
    public static void validateNext(SessionEntry entry, Map<String, ? extends SessionEntry> byId) {
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(byId, "byId must not be null");
        if (byId.containsKey(entry.id())) {
            throw new IllegalArgumentException("duplicate session entry id: " + entry.id());
        }
        if (entry.parentId() != null && !byId.containsKey(entry.parentId())) {
            throw new IllegalArgumentException(
                    "parent entry must appear earlier: " + entry.parentId());
        }
        if (entry instanceof LabelEntry label && !byId.containsKey(label.targetId())) {
            throw new IllegalArgumentException(
                    "label target must appear earlier: " + label.targetId());
        }
        if (entry instanceof BranchSummaryEntry summary && !byId.containsKey(summary.fromId())) {
            throw new IllegalArgumentException(
                    "branch summary source must appear earlier: " + summary.fromId());
        }
        if (entry instanceof CompactionEntry compaction) {
            var current = entry.parentId() == null ? null : byId.get(entry.parentId());
            boolean found = false;
            while (current != null) {
                if (current.id().equals(compaction.firstKeptEntryId())) {
                    found = isContextVisible(current);
                    break;
                }
                current = current.parentId() == null ? null : byId.get(current.parentId());
            }
            if (!found) {
                throw new IllegalArgumentException(
                        "compaction firstKeptEntryId must be a context-visible ancestor: "
                                + compaction.firstKeptEntryId());
            }
        }
    }

    /** Return a structural copy, including mutable JSON held by messages. */
    public static SessionEntry copy(SessionEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        return switch (entry) {
            case SessionMessageEntry message -> new SessionMessageEntry(
                    message.id(), message.parentId(), message.timestamp(), message.message());
            case ModelChangeEntry model -> new ModelChangeEntry(
                    model.id(), model.parentId(), model.timestamp(), model.model());
            case ThinkingLevelChangeEntry thinking -> new ThinkingLevelChangeEntry(
                    thinking.id(), thinking.parentId(), thinking.timestamp(), thinking.thinkingLevel());
            case SessionInfoEntry info -> new SessionInfoEntry(
                    info.id(), info.parentId(), info.timestamp(), info.name());
            case LabelEntry label -> new LabelEntry(
                    label.id(), label.parentId(), label.timestamp(), label.targetId(), label.label());
            case CompactionEntry compaction -> new CompactionEntry(
                    compaction.id(), compaction.parentId(), compaction.timestamp(),
                    compaction.summary(), compaction.firstKeptEntryId(), compaction.tokensBefore(),
                    compaction.tokenEstimateSource(), compaction.summaryModel(), compaction.usage(),
                    compaction.details());
            case BranchSummaryEntry summary -> new BranchSummaryEntry(
                    summary.id(), summary.parentId(), summary.timestamp(), summary.fromId(),
                    summary.summary(), summary.summaryModel(), summary.usage(), summary.details());
        };
    }

    private static boolean isContextVisible(SessionEntry entry) {
        return entry instanceof SessionMessageEntry || entry instanceof BranchSummaryEntry;
    }

    /** Return structural copies in source order. */
    public static List<SessionEntry> copyAll(List<? extends SessionEntry> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        var copy = new ArrayList<SessionEntry>(entries.size());
        entries.forEach(entry -> copy.add(copy(entry)));
        return List.copyOf(copy);
    }
}
