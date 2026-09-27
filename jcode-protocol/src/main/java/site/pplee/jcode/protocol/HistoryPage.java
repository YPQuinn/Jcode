package site.pplee.jcode.protocol;

import java.util.List;
import java.util.Objects;

/** Newest-first page on one fixed history parent chain. */
public record HistoryPage(
        String sessionId, String headEntryId, String nextBeforeEntryId,
        List<HistoryEntryView> entries
) {
    public HistoryPage {
        ProtocolIds.require(sessionId, "sessionId");
        entries = List.copyOf(Objects.requireNonNull(entries));
    }
}
