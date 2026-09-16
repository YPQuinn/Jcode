package site.pplee.jcode.ai.message;

import java.util.Objects;

/**
 * Opaque, provider-neutral replay state attached to a single content block.
 * {@code format} names a dialect and version; {@code payload} is interpreted
 * only by the adapter that wrote it. There is no map, property access, or
 * cross-provider decoder.
 *
 * <p>{@code toString()} redacts the payload so it cannot leak into logs.
 */
public record ModelReplayState(
        String format,
        String payload
) {
    public ModelReplayState {
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (format.isBlank()) {
            throw new IllegalArgumentException("format must not be blank");
        }
        if (payload.isBlank()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
    }

    @Override
    public String toString() {
        return "ModelReplayState[format=" + format + ", payload=***]";
    }
}
