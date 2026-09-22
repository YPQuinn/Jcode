package site.pplee.jcode.codingagent.session;

import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;

import java.time.Instant;
import java.util.Objects;

/** Summary of work left on another branch, appended at a selected target. */
public record BranchSummaryEntry(
        String id,
        String parentId,
        Instant timestamp,
        String fromId,
        String summary,
        ModelRef summaryModel,
        Usage usage,
        SummaryDetails details
) implements SessionEntry {
    public static final String TYPE = "branch_summary";

    public BranchSummaryEntry {
        SessionEntries.validateBase(id, parentId, timestamp);
        Objects.requireNonNull(fromId, "fromId must not be null");
        if (fromId.isBlank()) {
            throw new IllegalArgumentException("fromId must not be blank");
        }
        Objects.requireNonNull(summary, "summary must not be null");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        Objects.requireNonNull(summaryModel, "summaryModel must not be null");
        Objects.requireNonNull(usage, "usage must not be null");
        Objects.requireNonNull(details, "details must not be null");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
