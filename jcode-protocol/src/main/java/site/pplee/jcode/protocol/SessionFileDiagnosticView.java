package site.pplee.jcode.protocol;

/** Content-free failure or recovery diagnostic from one session file. */
public record SessionFileDiagnosticView(
        String fileRef, String kind, long lineNumber, long byteOffset, String detail
) {
    public SessionFileDiagnosticView {
        ProtocolIds.require(fileRef, "fileRef");
        ProtocolIds.require(kind, "kind");
        ProtocolIds.require(detail, "detail");
    }
}
