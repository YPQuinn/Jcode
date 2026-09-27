package site.pplee.jcode.protocol;

/** Bounded display projection of one accepted history node. */
public record HistoryEntryView(
        String entryId, String parentId, String type, String role,
        String text, boolean textTruncated
) {
    public HistoryEntryView {
        ProtocolIds.require(entryId, "entryId");
        ProtocolIds.require(type, "type");
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
    }
}
