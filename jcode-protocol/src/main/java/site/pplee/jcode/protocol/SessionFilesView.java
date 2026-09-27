package site.pplee.jcode.protocol;

import java.util.List;
import java.util.Objects;

/** One explicit workspace scan, without opening discovered files for writing. */
public record SessionFilesView(
        String workspaceId,
        List<SessionFileView> sessions,
        List<SessionFileDiagnosticView> diagnostics
) {
    public SessionFilesView {
        ProtocolIds.require(workspaceId, "workspaceId");
        sessions = List.copyOf(Objects.requireNonNull(sessions));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics));
    }
}
