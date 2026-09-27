package site.pplee.jcode.protocol;

/** Observable lifecycle of one tool approval. */
public enum ApprovalStatus {
    PENDING,
    ALLOWED,
    DENIED,
    EXPIRED,
    CANCELLED;

    public boolean terminal() {
        return this != PENDING;
    }
}
