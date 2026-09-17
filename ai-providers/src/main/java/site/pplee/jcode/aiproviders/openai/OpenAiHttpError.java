package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Package-private snapshot of a non-2xx Responses HTTP failure. Only the
 * retry allowlist headers, {@code x-request-id}, a 4 KiB body cap, and the
 * standard OpenAI error envelope are retained. Production parses must supply
 * a request-local {@link OpenAiSecretRedactor} so retained body, envelope,
 * request id, and diagnostic text are already cleaned. Retry headers and
 * status stay raw and are never decided from message text. Request headers,
 * credentials, and exception objects never enter this type.
 */
final class OpenAiHttpError {
    static final int MAX_BODY_BYTES = 4096;
    static final String SHOULD_RETRY_HEADER = "x-should-retry";
    static final String RETRY_AFTER_MS_HEADER = "retry-after-ms";
    static final String RETRY_AFTER_HEADER = "retry-after";

    private final int status;
    private final Optional<String> shouldRetry;
    private final Optional<String> retryAfterMs;
    private final Optional<String> retryAfter;
    private final Optional<String> requestId;
    private final String truncatedBody;
    private final boolean bodyTruncated;
    private final Optional<String> errorCode;
    private final Optional<String> errorMessage;
    private final Optional<String> errorType;
    private final String diagnosticMessage;

    private OpenAiHttpError(
            int status,
            Optional<String> shouldRetry,
            Optional<String> retryAfterMs,
            Optional<String> retryAfter,
            Optional<String> requestId,
            String truncatedBody,
            boolean bodyTruncated,
            Optional<String> errorCode,
            Optional<String> errorMessage,
            Optional<String> errorType,
            String diagnosticMessage
    ) {
        this.status = status;
        this.shouldRetry = shouldRetry;
        this.retryAfterMs = retryAfterMs;
        this.retryAfter = retryAfter;
        this.requestId = requestId;
        this.truncatedBody = truncatedBody;
        this.bodyTruncated = bodyTruncated;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.errorType = errorType;
        this.diagnosticMessage = diagnosticMessage;
    }

    /**
     * Test convenience: retained text is not redacted. Production must call
     * {@link #parse(int, HttpHeaders, byte[], ObjectMapper, OpenAiSecretRedactor)}.
     */
    static OpenAiHttpError parse(int status, HttpHeaders headers, byte[] rawBody, ObjectMapper mapper) {
        return parse(status, headers, rawBody, mapper, null);
    }

    static OpenAiHttpError parse(
            int status,
            HttpHeaders headers,
            byte[] rawBody,
            ObjectMapper mapper,
            OpenAiSecretRedactor redactor
    ) {
        Optional<String> shouldRetry = allowlisted(headers, SHOULD_RETRY_HEADER);
        Optional<String> retryAfterMs = allowlisted(headers, RETRY_AFTER_MS_HEADER);
        Optional<String> retryAfter = allowlisted(headers, RETRY_AFTER_HEADER);
        Optional<String> requestId = OpenAiResponseCorrelation.providerRequestId(headers);
        byte[] bounded = rawBody == null ? new byte[0] : rawBody;
        boolean truncated = bounded.length > MAX_BODY_BYTES;
        if (truncated) {
            bounded = copyOf(bounded, MAX_BODY_BYTES);
        }
        String body = new String(bounded, StandardCharsets.UTF_8);
        Envelope envelope = readEnvelope(body, mapper);
        if (redactor != null) {
            requestId = requestId.map(redactor::redact);
            body = redactor.redact(body);
            envelope = envelope.redact(redactor);
        }
        return new OpenAiHttpError(
                status,
                shouldRetry,
                retryAfterMs,
                retryAfter,
                requestId,
                body,
                truncated,
                envelope.code,
                envelope.message,
                envelope.type,
                diagnostic(status, envelope, body, truncated));
    }

    int status() {
        return status;
    }

    Optional<String> retryAfterMs() {
        return retryAfterMs;
    }

    Optional<String> retryAfter() {
        return retryAfter;
    }

    Optional<String> requestId() {
        return requestId;
    }

    String truncatedBody() {
        return truncatedBody;
    }

    boolean bodyTruncated() {
        return bodyTruncated;
    }

    Optional<String> errorCode() {
        return errorCode;
    }

    Optional<String> errorMessage() {
        return errorMessage;
    }

    Optional<String> errorType() {
        return errorType;
    }

    /**
     * Stable terminal message. Contains status plus either the envelope
     * fields or a safely truncated body. Never includes request headers,
     * credentials, or a full exception.
     */
    String diagnosticMessage() {
        return diagnosticMessage;
    }

    /**
     * Explicit {@code x-should-retry} override. Only {@code true}/{@code false}
     * (case-insensitive) count; any other value is ignored so retry still
     * follows the status table.
     */
    Optional<Boolean> shouldRetryOverride() {
        if (shouldRetry.isEmpty()) {
            return Optional.empty();
        }
        String value = shouldRetry.get().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value)) {
            return Optional.of(Boolean.TRUE);
        }
        if ("false".equals(value)) {
            return Optional.of(Boolean.FALSE);
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "OpenAiHttpError[status=" + status
                + ", type=" + present(errorType)
                + ", code=" + present(errorCode)
                + ", requestId=" + (requestId.isPresent() ? "present" : "absent")
                + ", bodyBytes=" + truncatedBody.getBytes(StandardCharsets.UTF_8).length
                + (bodyTruncated ? ", truncated" : "")
                + "]";
    }

    private static Optional<String> allowlisted(HttpHeaders headers, String name) {
        if (headers == null) {
            return Optional.empty();
        }
        return headers.firstValue(name)
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }

    private static Envelope readEnvelope(String body, ObjectMapper mapper) {
        if (body == null || body.isBlank() || mapper == null) {
            return Envelope.EMPTY;
        }
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject()) {
                return Envelope.EMPTY;
            }
            JsonNode error = root.get("error");
            if (error == null || !error.isObject()) {
                return Envelope.EMPTY;
            }
            Optional<String> message = textual(error.get("message"));
            Optional<String> type = textual(error.get("type"));
            Optional<String> code = textual(error.get("code"));
            if (message.isEmpty() && type.isEmpty() && code.isEmpty()) {
                return Envelope.EMPTY;
            }
            return new Envelope(code, message, type);
        } catch (Exception ignored) {
            return Envelope.EMPTY;
        }
    }

    private static Optional<String> textual(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return Optional.empty();
        }
        String value = node.asText();
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static String diagnostic(int status, Envelope envelope, String body, boolean truncated) {
        if (!envelope.isEmpty()) {
            var sb = new StringBuilder("HTTP ").append(status);
            envelope.type.ifPresent(type -> sb.append(' ').append(type));
            envelope.code.ifPresent(code -> sb.append(" [").append(code).append(']'));
            envelope.message.ifPresent(message -> sb.append(": ").append(message));
            return sb.toString();
        }
        if (body == null || body.isBlank()) {
            return "HTTP " + status;
        }
        return "HTTP " + status + ": " + body + (truncated ? "... [truncated]" : "");
    }

    private static String present(Optional<String> value) {
        return value.isPresent() ? "present" : "absent";
    }

    private static byte[] copyOf(byte[] source, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(source, 0, copy, 0, length);
        return copy;
    }

    private record Envelope(Optional<String> code, Optional<String> message, Optional<String> type) {
        static final Envelope EMPTY = new Envelope(Optional.empty(), Optional.empty(), Optional.empty());

        Envelope {
            code = code == null ? Optional.empty() : code;
            message = message == null ? Optional.empty() : message;
            type = type == null ? Optional.empty() : type;
        }

        boolean isEmpty() {
            return code.isEmpty() && message.isEmpty() && type.isEmpty();
        }

        Envelope redact(OpenAiSecretRedactor redactor) {
            if (redactor == null || isEmpty()) {
                return this;
            }
            return new Envelope(
                    code.map(redactor::redact),
                    message.map(redactor::redact),
                    type.map(redactor::redact));
        }
    }
}
