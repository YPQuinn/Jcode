package site.pplee.jcode.codingagent.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only point-in-time view of one session tree. Returned entries are
 * structural copies, including mutable JSON carried by standard messages.
 */
public final class SessionSnapshot {
    private final SessionHeader header;
    private final List<SessionEntry> entries;
    private final Map<String, SessionEntry> byId;
    private final Map<String, Integer> appendOrder;
    private final Map<String, List<SessionEntry>> childrenByParent;
    private final String currentEntryId;
    private final String name;
    private final Map<String, String> labels;

    /** Create a defensive read-only view from validated append-order entries. */
    public SessionSnapshot(
            SessionHeader header,
            List<? extends SessionEntry> entries,
            String currentEntryId,
            String name,
            Map<String, String> labels
    ) {
        this.header = Objects.requireNonNull(header, "header must not be null");
        this.entries = SessionEntries.copyAll(entries);
        this.byId = new LinkedHashMap<>();
        this.appendOrder = new HashMap<>();
        for (int index = 0; index < this.entries.size(); index++) {
            var entry = this.entries.get(index);
            SessionEntries.validateNext(entry, byId);
            byId.put(entry.id(), entry);
            appendOrder.put(entry.id(), index);
        }
        var mutableChildren = new HashMap<String, List<SessionEntry>>();
        for (var entry : this.entries) {
            mutableChildren.computeIfAbsent(entry.parentId(), ignored -> new ArrayList<>()).add(entry);
        }
        mutableChildren.replaceAll((ignored, children) -> children.stream()
                .sorted(displayOrder())
                .toList());
        this.childrenByParent = Collections.unmodifiableMap(mutableChildren);
        if (currentEntryId != null && !byId.containsKey(currentEntryId)) {
            throw new IllegalArgumentException("unknown current entry id: " + currentEntryId);
        }
        this.currentEntryId = currentEntryId;
        this.name = name;
        this.labels = Map.copyOf(Objects.requireNonNull(labels, "labels must not be null"));
    }

    public SessionHeader header() {
        return header;
    }

    /** Entries in append order. */
    public List<SessionEntry> entries() {
        return SessionEntries.copyAll(entries);
    }

    public Optional<String> currentEntryId() {
        return Optional.ofNullable(currentEntryId);
    }

    public Optional<SessionEntry> currentEntry() {
        return currentEntryId == null ? Optional.empty() : entry(currentEntryId);
    }

    public Optional<SessionEntry> entry(String entryId) {
        Objects.requireNonNull(entryId, "entryId must not be null");
        var entry = byId.get(entryId);
        return entry == null ? Optional.empty() : Optional.of(SessionEntries.copy(entry));
    }

    /**
     * Children of the supplied parent in display order. A null parent selects
     * roots. Ordering is timestamp first and append order for equal timestamps.
     */
    public List<SessionEntry> children(String parentId) {
        if (parentId != null && !byId.containsKey(parentId)) {
            throw new IllegalArgumentException("unknown session entry id: " + parentId);
        }
        return childrenByParent.getOrDefault(parentId, List.of()).stream()
                .map(SessionEntries::copy)
                .toList();
    }

    /** Root-to-node path selected by entry id. */
    public List<SessionEntry> branch(String entryId) {
        Objects.requireNonNull(entryId, "entryId must not be null");
        var current = byId.get(entryId);
        if (current == null) {
            throw new IllegalArgumentException("unknown session entry id: " + entryId);
        }
        var reversed = new ArrayList<SessionEntry>();
        while (current != null) {
            reversed.add(SessionEntries.copy(current));
            current = current.parentId() == null ? null : byId.get(current.parentId());
        }
        return reversed.reversed().stream().toList();
    }

    /** Current root-to-leaf path, empty when the leaf is reset before all roots. */
    public List<SessionEntry> currentBranch() {
        return currentEntryId == null ? List.of() : branch(currentEntryId);
    }

    /** Entire forest in display order. Multiple roots are allowed. */
    public List<SessionTreeNode> tree() {
        var nodesById = new HashMap<String, SessionTreeNode>();
        for (int index = entries.size() - 1; index >= 0; index--) {
            var entry = entries.get(index);
            var children = childrenByParent.getOrDefault(entry.id(), List.of()).stream()
                    .map(child -> nodesById.get(child.id()))
                    .toList();
            nodesById.put(entry.id(), new SessionTreeNode(entry, labels.get(entry.id()), children));
        }
        return childrenByParent.getOrDefault(null, List.of()).stream()
                .map(root -> nodesById.get(root.id()))
                .toList();
    }

    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    public Optional<String> label(String entryId) {
        Objects.requireNonNull(entryId, "entryId must not be null");
        if (!byId.containsKey(entryId)) {
            throw new IllegalArgumentException("unknown session entry id: " + entryId);
        }
        return Optional.ofNullable(labels.get(entryId));
    }

    private Comparator<SessionEntry> displayOrder() {
        return Comparator.comparing(SessionEntry::timestamp)
                .thenComparingInt(entry -> appendOrder.get(entry.id()));
    }
}
