package site.pplee.jcode.codingagent.session;

import com.fasterxml.jackson.databind.JsonNode;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.codingagent.message.CustomAgentMessage;
import site.pplee.jcode.codingagent.internal.SnapshotMapper;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Persisted extension message whose content is visible to the model. */
public record CustomMessageEntry(
        String id,
        String parentId,
        Instant timestamp,
        String extensionId,
        String customType,
        List<Content> content,
        JsonNode details,
        boolean display
) implements SessionEntry {
    public static final String TYPE = "custom_message";

    public CustomMessageEntry {
        SessionEntries.validateBase(id, parentId, timestamp);
        extensionId = requireName(extensionId, "extensionId");
        customType = requireName(customType, "customType");
        content = SnapshotMapper.contents(Objects.requireNonNull(content, "content must not be null"));
        for (var value : content) {
            if (!(value instanceof Content.Text || value instanceof Content.Image)) {
                throw new IllegalArgumentException(
                        "custom message content supports only text and image values");
            }
        }
        details = details == null ? null : details.deepCopy();
    }

    @Override
    public String type() {
        return TYPE;
    }

    public CustomAgentMessage message() {
        return new CustomAgentMessage(
                extensionId, customType, content, details, display, timestamp);
    }

    @Override
    public List<Content> content() {
        return SnapshotMapper.contents(content);
    }

    @Override
    public JsonNode details() {
        return details == null ? null : details.deepCopy();
    }

    private static String requireName(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

}
