package site.pplee.jcode.codingagent.tool;

import java.util.List;
import java.util.Objects;

/** Arguments accepted by the local {@code edit} tool. */
public record EditToolArguments(String path, List<EditReplacement> edits) {
    public static final int MAX_REPLACEMENTS = 100;

    public EditToolArguments {
        path = FileToolSupport.validatePath(path);
        Objects.requireNonNull(edits, "edits must not be null");
        edits = List.copyOf(edits);
        if (edits.isEmpty()) {
            throw new IllegalArgumentException("edits must contain at least one replacement");
        }
        if (edits.size() > MAX_REPLACEMENTS) {
            throw new IllegalArgumentException("edits exceeds the 100 replacement limit");
        }
    }
}
