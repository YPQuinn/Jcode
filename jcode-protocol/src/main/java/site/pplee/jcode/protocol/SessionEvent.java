package site.pplee.jcode.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** One ordered, replayable projection update with transport-neutral JSON data. */
public record SessionEvent(
        int schemaVersion,
        String sessionId,
        String runId,
        EventCursor cursor,
        EventType type,
        JsonNode data
) {
    public SessionEvent {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported event schemaVersion");
        }
        ProtocolIds.require(sessionId, "sessionId");
        Objects.requireNonNull(cursor, "cursor must not be null");
        Objects.requireNonNull(type, "type must not be null");
        data = Objects.requireNonNull(data, "data must not be null").deepCopy();
    }

    public SessionEvent(
            String sessionId,
            String runId,
            EventCursor cursor,
            EventType type,
            JsonNode data
    ) {
        this(1, sessionId, runId, cursor, type, data);
    }

    @Override
    public JsonNode data() {
        return data.deepCopy();
    }
}
