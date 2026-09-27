package site.pplee.jcode.protocol;

/** Current history leaf after a product maintenance operation. */
public record HistoryView(String leafId) {
    public HistoryView {
        ProtocolIds.require(leafId, "leafId");
    }
}
