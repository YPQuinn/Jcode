package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.message.ModelReplayState;

import java.util.Optional;

/**
 * Versioned codec for OpenAI Responses replay envelopes stored in
 * {@link ModelReplayState}. Unknown, malformed, or stale state is ignored
 * so the planner can degrade safely instead of failing the request.
 */
final class OpenAiReplayStateCodec {
    static final String REASONING_FORMAT = "openai-responses/reasoning-item-v1";
    static final String MESSAGE_FORMAT = "openai-responses/message-item-v1";
    private static final int VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAiReplayStateCodec() {
    }

    record MessageReplay(String itemId, String phase) {
    }

    static ModelReplayState encodeReasoning(String visibleText, JsonNode item) {
        if (item == null || !item.isObject()) {
            return null;
        }
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("v", VERSION);
        envelope.put("digest", OpenAiToolCallIds.sha256Hex(visibleText == null ? "" : visibleText));
        envelope.set("item", item.deepCopy());
        return new ModelReplayState(REASONING_FORMAT, write(envelope));
    }

    static ModelReplayState encodeMessage(String visibleText, String itemId, String phase) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("v", VERSION);
        envelope.put("digest", OpenAiToolCallIds.sha256Hex(visibleText == null ? "" : visibleText));
        envelope.put("id", itemId);
        if (phase != null && !phase.isBlank()) {
            envelope.put("phase", phase);
        }
        return new ModelReplayState(MESSAGE_FORMAT, write(envelope));
    }

    static Optional<JsonNode> decodeReasoning(ModelReplayState state, String visibleText) {
        return envelope(state, REASONING_FORMAT, visibleText)
                .map(node -> node.get("item"))
                .filter(item -> item != null && item.isObject())
                .map(JsonNode::deepCopy);
    }

    static Optional<MessageReplay> decodeMessage(ModelReplayState state, String visibleText) {
        return envelope(state, MESSAGE_FORMAT, visibleText).flatMap(node -> {
            JsonNode id = node.get("id");
            if (id == null || !id.isTextual() || id.asText().isBlank()) {
                return Optional.empty();
            }
            String phase = node.path("phase").isTextual() ? node.get("phase").asText() : null;
            if (phase != null && phase.isBlank()) {
                phase = null;
            }
            return Optional.of(new MessageReplay(id.asText(), phase));
        });
    }

    static boolean hasEncryptedContent(JsonNode item) {
        if (item == null) {
            return false;
        }
        JsonNode encrypted = item.get("encrypted_content");
        return encrypted != null && encrypted.isTextual() && !encrypted.asText().isBlank();
    }

    private static Optional<ObjectNode> envelope(ModelReplayState state, String expectedFormat, String visibleText) {
        if (state == null || !expectedFormat.equals(state.format())) {
            return Optional.empty();
        }
        try {
            JsonNode parsed = MAPPER.readTree(state.payload());
            if (parsed == null || !parsed.isObject()) {
                return Optional.empty();
            }
            if (parsed.path("v").asInt(-1) != VERSION) {
                return Optional.empty();
            }
            String digest = parsed.path("digest").asText("");
            if (digest.isBlank() || !digest.equals(OpenAiToolCallIds.sha256Hex(visibleText == null ? "" : visibleText))) {
                return Optional.empty();
            }
            return Optional.of((ObjectNode) parsed.deepCopy());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode replay state", e);
        }
    }
}
