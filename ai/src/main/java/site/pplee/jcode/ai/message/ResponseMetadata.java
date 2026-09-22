package site.pplee.jcode.ai.message;

import java.util.Objects;
import java.util.Optional;

/**
 * Bounded, provider-neutral metadata for one assistant result. Carries
 * optional response correlation ids, an optional raw terminal reason, and an
 * optional standardized failure classification. There is no map, raw response
 * body, or header dump.
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
        Optional<String> rawTerminalReason,
        Optional<ModelFailureKind> failureKind
) {
    /** Maximum UTF-16 length of {@link #responseId()}. */
    public static final int MAX_RESPONSE_ID_LENGTH = 256;
    /** Maximum UTF-16 length of {@link #providerRequestId()}. */
    public static final int MAX_PROVIDER_REQUEST_ID_LENGTH = 256;
    /** Maximum UTF-16 length of {@link #rawTerminalReason()}. */
    public static final int MAX_RAW_TERMINAL_REASON_LENGTH = 256;

    private static final ResponseMetadata EMPTY = new ResponseMetadata(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    public ResponseMetadata {
        responseId = normalize("responseId", responseId, MAX_RESPONSE_ID_LENGTH);
        providerRequestId = normalize(
                "providerRequestId", providerRequestId, MAX_PROVIDER_REQUEST_ID_LENGTH);
        rawTerminalReason = normalize(
                "rawTerminalReason", rawTerminalReason, MAX_RAW_TERMINAL_REASON_LENGTH);
        Objects.requireNonNull(failureKind, "failureKind must not be null");
    }

    /** Compatibility constructor for correlation-only metadata. */
    public ResponseMetadata(
            Optional<String> responseId,
            Optional<String> providerRequestId,
            Optional<String> rawTerminalReason
    ) {
        this(responseId, providerRequestId, rawTerminalReason, Optional.empty());
    }

    /** No response metadata. */
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
                Optional.ofNullable(rawTerminalReason),
                Optional.empty());
    }

    /** Metadata from possibly-blank correlation values and a classified failure. */
    public static ResponseMetadata of(
            String responseId,
            String providerRequestId,
            String rawTerminalReason,
            ModelFailureKind failureKind
    ) {
        return new ResponseMetadata(
                Optional.ofNullable(responseId),
                Optional.ofNullable(providerRequestId),
                Optional.ofNullable(rawTerminalReason),
                Optional.ofNullable(failureKind));
    }

    public boolean isEmpty() {
        return responseId.isEmpty() && providerRequestId.isEmpty()
                && rawTerminalReason.isEmpty() && failureKind.isEmpty();
    }

    @Override
    public String toString() {
        String base = "ResponseMetadata[responseId=" + present(responseId)
                + ", providerRequestId=" + present(providerRequestId)
                + ", rawTerminalReason=" + present(rawTerminalReason);
        return failureKind.isEmpty()
                ? base + "]"
                : base + ", failureKind=" + failureKind.orElseThrow().name() + "]";
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
