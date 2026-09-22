package site.pplee.jcode.codingagent.compaction;

import site.pplee.jcode.ai.message.Usage;

import java.util.Objects;
import java.util.Optional;

/** Outcome of an explicit compaction request. */
public record CompactionResult(
        CompactionStatus status,
        Optional<String> entryId,
        Optional<String> firstKeptEntryId,
        ContextUsageEstimate before,
        ContextUsageEstimate after,
        Usage summaryUsage,
        String reason
) {
    public CompactionResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(entryId, "entryId must not be null");
        Objects.requireNonNull(firstKeptEntryId, "firstKeptEntryId must not be null");
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        Objects.requireNonNull(summaryUsage, "summaryUsage must not be null");
    }
}
