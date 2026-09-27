package site.pplee.jcode.protocol;

import java.util.Objects;

/** Content-free summary of a discoverable session file. */
public record SessionFileView(
        String fileRef, String sessionId, String name,
        String created, String modified, long messageCount
) {
    public SessionFileView {
        ProtocolIds.require(fileRef, "fileRef");
        ProtocolIds.require(sessionId, "sessionId");
        Objects.requireNonNull(created, "created must not be null");
        Objects.requireNonNull(modified, "modified must not be null");
        if (messageCount < 0) {
            throw new IllegalArgumentException("messageCount must not be negative");
        }
    }
}
