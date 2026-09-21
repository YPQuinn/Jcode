package site.pplee.jcode.codingagent.session;

import java.util.List;
import java.util.Objects;

/** Immutable display node with the currently resolved label and child nodes. */
public record SessionTreeNode(
        SessionEntry entry,
        String label,
        List<SessionTreeNode> children
) {
    public SessionTreeNode {
        entry = SessionEntries.copy(Objects.requireNonNull(entry, "entry must not be null"));
        children = List.copyOf(Objects.requireNonNull(children, "children must not be null"));
    }

    @Override
    public SessionEntry entry() {
        return SessionEntries.copy(entry);
    }
}
