package site.pplee.jcode.codingagent.compaction;

import java.util.Objects;
import java.util.Optional;

/** Outcome of moving to an existing node with optional branch context. */
public record BranchSummaryResult(
        String fromId,
        String targetId,
        String finalLeafId,
        Optional<String> summaryEntryId
) {
    public BranchSummaryResult {
        Objects.requireNonNull(fromId, "fromId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(finalLeafId, "finalLeafId must not be null");
        Objects.requireNonNull(summaryEntryId, "summaryEntryId must not be null");
    }

    public boolean generatedSummary() {
        return summaryEntryId.isPresent();
    }
}
