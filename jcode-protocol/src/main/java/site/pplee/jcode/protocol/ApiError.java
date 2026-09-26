package site.pplee.jcode.protocol;

import java.util.Objects;

/** Transport-independent rejection returned before a new command is accepted. */
public record ApiError(ErrorCode code, String message) {
    public ApiError {
        Objects.requireNonNull(code, "code must not be null");
        ProtocolIds.require(message, "message");
    }
}
