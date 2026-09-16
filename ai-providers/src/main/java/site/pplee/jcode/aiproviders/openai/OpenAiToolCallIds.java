package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Versioned codec for OpenAI Responses tool-call identities. The standard
 * {@code site.pplee.jcode.ai.message.Content.ToolCall.id} field carries a
 * single string, while the Responses API distinguishes the function
 * {@code call_id} from the output item {@code id}. Encoded ids use the form
 * {@code oai1:<base64url JSON>} so both parts survive replay without
 * delimiter ambiguity.
 *
 * <p>Ids with the {@code oai1:} prefix are treated as encoded and must
 * decode cleanly; malformed encoded ids fail with
 * {@link IllegalArgumentException} so callers fail safe instead of
 * misinterpreting corrupt data as a raw id. Raw ids are accepted only when
 * they satisfy the OpenAI-safe charset and length constraints. Replayed item
 * ids are included only when already OpenAI-valid ({@code fc_} prefix, safe
 * charset, max 64 chars); otherwise they are omitted rather than sanitized
 * into a different id.
 */
final class OpenAiToolCallIds {
    private static final String PREFIX = "oai1:";
    private static final int MAX_ID_LENGTH = 64;
    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9_-]{1," + MAX_ID_LENGTH + "}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAiToolCallIds() {
    }

    /** Decoded parts of an encoded tool-call id. */
    record Decoded(String callId, String itemId) {
        Decoded {
            Objects.requireNonNull(callId, "callId must not be null");
        }
    }

    /** Encode a call id plus optional item id into the versioned form. */
    static String encode(String callId, String itemId) {
        if (!isSafe(callId)) {
            throw new IllegalArgumentException("call id is not OpenAI-safe: " + callId);
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("callId", callId);
        if (itemId != null && !itemId.isBlank()) {
            if (!isSafe(itemId)) {
                throw new IllegalArgumentException("item id is not OpenAI-safe: " + itemId);
            }
            node.put("itemId", itemId);
        }
        try {
            String json = MAPPER.writeValueAsString(node);
            return PREFIX + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to encode tool-call id", e);
        }
    }

    /**
     * Decode a versioned id. Empty for raw (non-encoded) ids; throws
     * {@link IllegalArgumentException} for any malformed encoded payload so
     * corrupt data never falls back to raw handling.
     */
    static Optional<Decoded> decode(String id) {
        if (id == null || !id.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(id.substring(PREFIX.length()));
            JsonNode node = MAPPER.readTree(bytes);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("encoded payload is not an object");
            }
            String callId = node.path("callId").asText(null);
            if (!isSafe(callId)) {
                throw new IllegalArgumentException("encoded payload has an invalid callId");
            }
            String itemId = node.has("itemId") && node.get("itemId").isTextual()
                    ? node.get("itemId").asText() : null;
            if (itemId != null && !isSafe(itemId)) {
                throw new IllegalArgumentException("encoded payload has an invalid itemId");
            }
            return Optional.of(new Decoded(callId, itemId));
        } catch (RuntimeException | IOException e) {
            throw new IllegalArgumentException("malformed encoded tool-call id: " + id, e);
        }
    }

    /**
     * The function call id to send back to the API. Decodes encoded ids;
     * raw ids pass through only when OpenAI-safe, otherwise this fails so a
     * corrupt id never reaches the wire.
     */
    static String callId(String id) {
        var decoded = decode(id);
        if (decoded.isPresent()) {
            return decoded.get().callId();
        }
        if (!isSafe(id)) {
            throw new IllegalArgumentException("raw tool-call id is not OpenAI-safe: " + id);
        }
        return id;
    }

    /** The output item id from an encoded id, or null for raw ids / absent parts. */
    static String itemId(String id) {
        return decode(id).map(Decoded::itemId).orElse(null);
    }

    /**
     * The item id usable for replay, or null when the decoded item id is not
     * already OpenAI-valid ({@code fc_} prefix, safe charset, max 64 chars).
     * Never fabricates or sanitizes a different id.
     */
    static String validItemId(String itemId) {
        if (itemId == null || !itemId.startsWith("fc_")) {
            return null;
        }
        return isSafe(itemId) ? itemId : null;
    }

    /** True when {@code id} uses the versioned {@code oai1:} encoding prefix. */
    static boolean isEncoded(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    /** True when {@code id} matches the OpenAI-safe charset and length. */
    static boolean isSafeId(String id) {
        return isSafe(id);
    }

    /**
     * Deterministic OpenAI-safe call id for a foreign or unsafe raw id.
     * The same original value always maps to the same 64-character-or-shorter id.
     */
    static String hashedForeignCallId(String original) {
        Objects.requireNonNull(original, "original must not be null");
        String hex = sha256Hex(original);
        String hashed = "call_jcode_" + hex;
        return hashed.length() <= MAX_ID_LENGTH ? hashed : hashed.substring(0, MAX_ID_LENGTH);
    }

    static String sha256Hex(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private static boolean isSafe(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }
}
