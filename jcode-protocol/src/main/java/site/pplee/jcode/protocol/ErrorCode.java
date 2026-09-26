package site.pplee.jcode.protocol;

/** Stable command rejection categories; run failures remain queryable run results. */
public enum ErrorCode {
    SESSION_BUSY,
    SESSION_CLOSED,
    STATE_CONFLICT,
    RUN_INPUT_CLOSED,
    IDEMPOTENCY_CONFLICT,
    CAPACITY_EXCEEDED,
    NOT_FOUND,
    INVALID_ARGUMENT,
    INTERNAL_ERROR
}
