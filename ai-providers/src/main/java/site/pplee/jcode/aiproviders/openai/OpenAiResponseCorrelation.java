package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.JsonNode;
import site.pplee.jcode.ai.message.ResponseMetadata;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts bounded correlation metadata from a Responses terminal object
 * and the official {@code x-request-id} response header. Request headers,
 * credentials, error bodies, and any non-allowlisted header never enter
 * {@link ResponseMetadata}.
 */
final class OpenAiResponseCorrelation {
    static final String REQUEST_ID_HEADER = "x-request-id";

    private OpenAiResponseCorrelation() {
    }

    /**
     * Official OpenAI request id from response headers. Header names are
     * matched case-insensitively. Only {@code x-request-id} is read.
     */
    static Optional<String> providerRequestId(HttpHeaders headers) {
        if (headers == null) {
            return Optional.empty();
        }
        return firstNonBlank(headers.firstValue(REQUEST_ID_HEADER));
    }

    /**
     * Allowlisted request id from a raw header map. Names are matched
     * case-insensitively; values of other headers are ignored.
     */
    static Optional<String> providerRequestId(Map<String, List<String>> headers) {
        if (headers == null) {
            return Optional.empty();
        }
        for (var entry : headers.entrySet()) {
            if (!REQUEST_ID_HEADER.equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                return Optional.empty();
            }
            String first = values.get(0);
            return first == null || first.isBlank() ? Optional.empty() : Optional.of(first);
        }
        return Optional.empty();
    }

    /**
     * Correlation metadata for a terminal {@code response} object.
     * Oversize ids or reasons fail as a protocol mapping error.
     * A present non-string terminal {@code id} is a protocol error.
     * When the terminal id is absent, null, or blank, {@code fallbackResponseId}
     * from {@code response.created} is used.
     *
     * @param includeRawReason {@code true} for {@code response.completed},
     *                         {@code response.incomplete}, and
     *                         {@code response.failed}; {@code false} for
     *                         ordinary SSE {@code error} events
     */
    static ResponseMetadata fromResponse(
            JsonNode response,
            Optional<String> providerRequestId,
            boolean includeRawReason,
            String fallbackResponseId
    ) {
        Optional<String> requestId = providerRequestId == null ? Optional.empty() : providerRequestId;
        String responseId = terminalResponseId(response, fallbackResponseId);
        String rawReason = includeRawReason ? rawTerminalReason(response) : null;
        try {
            return ResponseMetadata.of(responseId, requestId.orElse(null), rawReason);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("protocol mapping error: " + e.getMessage(), e);
        }
    }

    /**
     * Header-only metadata for an ordinary SSE {@code error} event. The
     * event payload is not a response object and must not invent a
     * correlation id or raw reason.
     */
    static ResponseMetadata fromSseError(Optional<String> providerRequestId) {
        return knownSoFar(null, providerRequestId);
    }

    /**
     * Progress snapshot from a created response id and the allowlisted
     * request id. No raw reason and no body/header dump.
     */
    static ResponseMetadata knownSoFar(String responseId, Optional<String> providerRequestId) {
        Optional<String> requestId = providerRequestId == null ? Optional.empty() : providerRequestId;
        try {
            return ResponseMetadata.of(responseId, requestId.orElse(null), null);
        } catch (IllegalArgumentException e) {
            return ResponseMetadata.empty();
        }
    }

    /**
     * Safe metadata for transport or non-2xx failures. Only the allowlisted
     * {@code x-request-id} header may be recorded. The error body is never
     * parsed or copied into metadata.
     */
    static ResponseMetadata fromHttpFailure(Optional<String> providerRequestId) {
        return fromSseError(providerRequestId);
    }

    static String rawTerminalReason(JsonNode response) {
        if (response == null || !response.isObject()) {
            return null;
        }
        String status = textual(response.get("status"));
        String incomplete = textual(response.path("incomplete_details").get("reason"));
        if (status != null && incomplete != null) {
            return status + "." + incomplete;
        }
        return status != null ? status : incomplete;
    }

    /**
     * Records a {@code response.created} id. Absent, null, or blank is
     * ignored. A present non-string or oversize id is a protocol mapping
     * error. The value is not written to partial metadata.
     */
    static String createdResponseId(JsonNode idNode) {
        if (idNode == null || idNode.isNull()) {
            return null;
        }
        if (!idNode.isTextual()) {
            throw new IllegalStateException("protocol mapping error: response.created id is not a string");
        }
        String id = idNode.asText();
        if (id.isBlank()) {
            return null;
        }
        if (id.length() > ResponseMetadata.MAX_RESPONSE_ID_LENGTH) {
            throw new IllegalStateException(
                    "protocol mapping error: responseId exceeds "
                            + ResponseMetadata.MAX_RESPONSE_ID_LENGTH + " characters");
        }
        return id;
    }

    static String terminalResponseId(JsonNode response, String fallbackResponseId) {
        if (response == null || !response.isObject() || !response.has("id") || response.get("id").isNull()) {
            return fallbackResponseId;
        }
        JsonNode id = response.get("id");
        if (!id.isTextual()) {
            throw new IllegalStateException("protocol mapping error: response id is not a string");
        }
        String value = id.asText();
        return value.isBlank() ? fallbackResponseId : value;
    }

    private static String textual(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private static Optional<String> firstNonBlank(Optional<String> value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        String raw = value.get();
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw);
    }
}
