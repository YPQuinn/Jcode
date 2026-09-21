package site.pplee.jcode.codingagent.session;

import site.pplee.jcode.ai.model.ModelRef;

import java.time.Instant;
import java.util.Objects;

/** Session-tree node recording the model selected for subsequent activity. */
public record ModelChangeEntry(
        String id,
        String parentId,
        Instant timestamp,
        ModelRef model
) implements SessionEntry {
    public static final String TYPE = "model_change";

    public ModelChangeEntry {
        SessionEntries.validateBase(id, parentId, timestamp);
        Objects.requireNonNull(model, "model must not be null");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
