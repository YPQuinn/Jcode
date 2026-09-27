package site.pplee.jcode.protocol;

/** Locally configured workspace available to the service client. */
public record WorkspaceView(String workspaceId, String directory) {
    public WorkspaceView {
        ProtocolIds.require(workspaceId, "workspaceId");
        ProtocolIds.require(directory, "directory");
    }
}
