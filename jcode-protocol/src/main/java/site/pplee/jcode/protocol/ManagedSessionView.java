package site.pplee.jcode.protocol;

/** One currently managed file session; disk discovery is a separate query. */
public record ManagedSessionView(
        String sessionId, String workspaceId, String fileRef, String leafId
) {
    public ManagedSessionView {
        ProtocolIds.require(sessionId, "sessionId");
        ProtocolIds.require(workspaceId, "workspaceId");
        ProtocolIds.require(fileRef, "fileRef");
    }
}
