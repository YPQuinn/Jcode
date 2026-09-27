package site.pplee.jcode.protocol;

/** Stable command rejection categories; run failures remain queryable run results. */
public enum ErrorCode {
    SESSION_BUSY,
    SESSION_CLOSED,
    STATE_CONFLICT,
    RUN_INPUT_CLOSED,
    IDEMPOTENCY_CONFLICT,
    CAPACITY_EXCEEDED,
    CURSOR_EXPIRED,
    EPOCH_CHANGED,
    SUBSCRIBER_SLOW,
    APPROVAL_CLOSED,
    NOT_FOUND,
    INVALID_ARGUMENT,
    UNAUTHORIZED,
    ORIGIN_FORBIDDEN,
    METHOD_NOT_ALLOWED,
    SERVER_STOPPING,
    INTERNAL_ERROR
}
