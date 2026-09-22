package site.pplee.jcode.codingagent.session;

import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;

import java.time.Instant;
import java.util.Objects;

/** Append-only checkpoint summarizing an older prefix of one session branch. */
public record CompactionEntry(
        String id,
        String parentId,
        Instant timestamp,
        String summary,
        String firstKeptEntryId,
        long tokensBefore,
        TokenEstimateSource tokenEstimateSource,
        ModelRef summaryModel,
        Usage usage,
        SummaryDetails details
) implements SessionEntry {
    public static final String TYPE = "compaction";

    public CompactionEntry {
        SessionEntries.validateBase(id, parentId, timestamp);
        Objects.requireNonNull(summary, "summary must not be null");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        Objects.requireNonNull(firstKeptEntryId, "firstKeptEntryId must not be null");
        if (firstKeptEntryId.isBlank()) {
            throw new IllegalArgumentException("firstKeptEntryId must not be blank");
        }
        if (tokensBefore < 0) {
            throw new IllegalArgumentException("tokensBefore must not be negative");
        }
        Objects.requireNonNull(tokenEstimateSource, "tokenEstimateSource must not be null");
        Objects.requireNonNull(summaryModel, "summaryModel must not be null");
        Objects.requireNonNull(usage, "usage must not be null");
        Objects.requireNonNull(details, "details must not be null");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
