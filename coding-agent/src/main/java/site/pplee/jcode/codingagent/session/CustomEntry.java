package site.pplee.jcode.codingagent.session;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/** Extension state that remains queryable but never enters model context. */
public record CustomEntry(
        String id,
        String parentId,
        Instant timestamp,
        String extensionId,
        String customType,
        JsonNode data
) implements SessionEntry {
    public static final String TYPE = "custom";

    public CustomEntry {
        SessionEntries.validateBase(id, parentId, timestamp);
        extensionId = requireName(extensionId, "extensionId");
        customType = requireName(customType, "customType");
        data = data == null ? null : data.deepCopy();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public JsonNode data() {
        return data == null ? null : data.deepCopy();
    }

    private static String requireName(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
