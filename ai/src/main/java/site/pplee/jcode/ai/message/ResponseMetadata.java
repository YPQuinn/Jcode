package site.pplee.jcode.ai.message;

import java.util.Objects;
import java.util.Optional;

/**
 * Bounded, provider-neutral correlation metadata for one assistant result.
 * Carries an optional response correlation id, optional provider request id,
 * and optional raw terminal reason. There is no map, raw response body, or
 * header dump.
 *
 * <p>Each field has a hard length limit and is rejected when exceeded.
 * Blank values are treated as absent. {@link #empty()} is the default for
 * partial messages and compatibility constructors.
 *
 * <p>{@link #toString()} reports only presence, never the raw values.
 */
public record ResponseMetadata(
        Optional<String> responseId,
        Optional<String> providerRequestId,
        Optional<String> rawTerminalReason
) {
    /** Maximum UTF-16 length of {@link #responseId()}. */
    public static final int MAX_RESPONSE_ID_LENGTH = 256;
    /** Maximum UTF-16 length of {@link #providerRequestId()}. */
    public static final int MAX_PROVIDER_REQUEST_ID_LENGTH = 256;
    /** Maximum UTF-16 length of {@link #rawTerminalReason()}. */
    public static final int MAX_RAW_TERMINAL_REASON_LENGTH = 256;

    private static final ResponseMetadata EMPTY = new ResponseMetadata(
            Optional.empty(), Optional.empty(), Optional.empty());

    public ResponseMetadata {
        responseId = normalize("responseId", responseId, MAX_RESPONSE_ID_LENGTH);
        providerRequestId = normalize(
                "providerRequestId", providerRequestId, MAX_PROVIDER_REQUEST_ID_LENGTH);
        rawTerminalReason = normalize(
                "rawTerminalReason", rawTerminalReason, MAX_RAW_TERMINAL_REASON_LENGTH);
    }

    /** No correlation metadata. */
    public static ResponseMetadata empty() {
        return EMPTY;
    }

    /**
     * Metadata from possibly-blank values. Blank or {@code null} fields
     * become absent; oversize values are rejected.
     */
    public static ResponseMetadata of(String responseId, String providerRequestId, String rawTerminalReason) {
        return new ResponseMetadata(
                Optional.ofNullable(responseId),
                Optional.ofNullable(providerRequestId),
                Optional.ofNullable(rawTerminalReason));
    }

    public boolean isEmpty() {
        return responseId.isEmpty() && providerRequestId.isEmpty() && rawTerminalReason.isEmpty();
    }

    @Override
    public String toString() {
        return "ResponseMetadata[responseId=" + present(responseId)
                + ", providerRequestId=" + present(providerRequestId)
                + ", rawTerminalReason=" + present(rawTerminalReason) + "]";
    }

    private static Optional<String> normalize(String name, Optional<String> value, int maxLength) {
        Optional<String> present = Objects.requireNonNull(value, name + " must not be null");
        if (present.isEmpty()) {
            return Optional.empty();
        }
        String raw = present.get();
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        if (raw.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        }
        return Optional.of(raw);
    }

    private static String present(Optional<String> value) {
        return value.isPresent() ? "present" : "absent";
    }
}
