package site.pplee.jcode.app;

import site.pplee.jcode.protocol.ApiError;
import site.pplee.jcode.protocol.ErrorCode;

import java.util.Objects;

/** A command rejection with a stable client-facing error category. */
public final class ApiException extends RuntimeException {
    private final ApiError error;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        error = new ApiError(Objects.requireNonNull(code, "code must not be null"), message);
    }

    public ApiError error() {
        return error;
    }
}
