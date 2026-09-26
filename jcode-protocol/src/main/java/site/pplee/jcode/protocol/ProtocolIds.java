package site.pplee.jcode.protocol;

import java.util.Objects;

/** Shared validation for caller-supplied protocol identities. */
final class ProtocolIds {
    private ProtocolIds() {
    }

    static String require(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
