package site.pplee.jcode.codingagent.extension;

import com.fasterxml.jackson.databind.JsonNode;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.codingagent.internal.SnapshotMapper;

import java.util.List;
import java.util.Objects;

/** Fixed command-produced records that can be committed to session history. */
public sealed interface CustomRecordDraft
        permits CustomRecordDraft.Data, CustomRecordDraft.Message {

    String customType();

    /** Model-invisible extension state. */
    record Data(String customType, JsonNode data) implements CustomRecordDraft {
        public Data {
            customType = requireType(customType);
            data = data == null ? null : data.deepCopy();
        }

        @Override
        public JsonNode data() {
            return data == null ? null : data.deepCopy();
        }
    }

    /** User-like extension content visible in future model requests. */
    record Message(
            String customType,
            List<Content> content,
            JsonNode details,
            boolean display
    ) implements CustomRecordDraft {
        public Message {
            customType = requireType(customType);
            content = SnapshotMapper.contents(
                    Objects.requireNonNull(content, "content must not be null"));
            for (var value : content) {
                if (!(value instanceof Content.Text || value instanceof Content.Image)) {
                    throw new IllegalArgumentException(
                            "custom message content supports only text and image values");
                }
            }
            details = details == null ? null : details.deepCopy();
        }

        @Override
        public List<Content> content() {
            return SnapshotMapper.contents(content);
        }

        @Override
        public JsonNode details() {
            return details == null ? null : details.deepCopy();
        }
    }

    private static String requireType(String value) {
        Objects.requireNonNull(value, "customType must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("customType must not be blank");
        }
        return value;
    }
}
