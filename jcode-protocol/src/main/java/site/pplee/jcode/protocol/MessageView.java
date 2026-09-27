package site.pplee.jcode.protocol;

import java.util.Objects;

/** Bounded display state for one message; complete messages link to history when available. */
public record MessageView(
        String messageId,
        String runId,
        String role,
        String text,
        boolean truncated,
        boolean complete,
        String entryId
) {
    public MessageView {
        ProtocolIds.require(messageId, "messageId");
        ProtocolIds.require(runId, "runId");
        ProtocolIds.require(role, "role");
        Objects.requireNonNull(text, "text must not be null");
        if (!complete && entryId != null) {
            throw new IllegalArgumentException("partial message cannot have an entryId");
        }
    }
}
