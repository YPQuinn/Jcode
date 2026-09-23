package site.pplee.jcode.codingagent.message;

import com.fasterxml.jackson.databind.JsonNode;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.codingagent.internal.SnapshotMapper;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Extension-owned user-like content with a fixed, provider-neutral envelope. */
public final class CustomAgentMessage implements AgentMessage {
    private final String extensionId;
    private final String customType;
    private final List<Content> content;
    private final JsonNode details;
    private final boolean display;
    private final Instant timestamp;

    public CustomAgentMessage(
            String extensionId,
            String customType,
            List<? extends Content> content,
            JsonNode details,
            boolean display,
            Instant timestamp
    ) {
        this.extensionId = requireName(extensionId, "extensionId");
        this.customType = requireName(customType, "customType");
        this.content = copyVisibleContent(content);
        this.details = details == null ? null : details.deepCopy();
        this.display = display;
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public String extensionId() {
        return extensionId;
    }

    public String customType() {
        return customType;
    }

    public List<Content> content() {
        return SnapshotMapper.contents(content);
    }

    public JsonNode details() {
        return details == null ? null : details.deepCopy();
    }

    public boolean display() {
        return display;
    }

    public Instant timestamp() {
        return timestamp;
    }

    private static List<Content> copyVisibleContent(List<? extends Content> content) {
        var copied = SnapshotMapper.contents(
                Objects.requireNonNull(content, "content must not be null"));
        for (var item : copied) {
            if (!(item instanceof Content.Text || item instanceof Content.Image)) {
                throw new IllegalArgumentException(
                        "custom message content supports only text and image values");
            }
        }
        return copied;
    }

    private static String requireName(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CustomAgentMessage message
                && extensionId.equals(message.extensionId)
                && customType.equals(message.customType)
                && content.equals(message.content)
                && Objects.equals(details, message.details)
                && display == message.display
                && timestamp.equals(message.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(extensionId, customType, content, details, display, timestamp);
    }
}
