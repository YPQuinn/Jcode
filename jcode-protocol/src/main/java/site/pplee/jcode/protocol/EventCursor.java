package site.pplee.jcode.protocol;

/** Position in one in-memory session event stream. */
public record EventCursor(String epoch, long seq) {
    public EventCursor {
        ProtocolIds.require(epoch, "epoch");
        if (seq < 0) {
            throw new IllegalArgumentException("seq must not be negative");
        }
    }
}
