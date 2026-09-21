package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.session.LabelEntry;
import site.pplee.jcode.codingagent.session.ModelChangeEntry;
import site.pplee.jcode.codingagent.session.SessionEntry;
import site.pplee.jcode.codingagent.session.SessionHeader;
import site.pplee.jcode.codingagent.session.SessionInfoEntry;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;
import site.pplee.jcode.codingagent.session.ThinkingLevelChangeEntry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Fixed, explicit JSON codec for Jcode session format version one. */
final class SessionCodec {
    private final ObjectMapper mapper;
    private final SessionMessageCodec messages;

    SessionCodec() {
        this.mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.messages = new SessionMessageCodec(mapper);
    }

    byte[] encodeHeader(SessionHeader header) throws JsonProcessingException {
        var object = mapper.createObjectNode();
        object.put("type", header.type());
        object.put("version", header.version());
        object.put("id", header.id().toString());
        object.put("timestamp", header.timestamp().toString());
        object.put("cwd", header.cwd().toString());
        return mapper.writeValueAsBytes(object);
    }

    SessionHeader decodeHeader(byte[] json) throws IOException {
        var object = SessionJson.requireObject(mapper.readTree(json), "header");
        var type = SessionJson.requireText(object, "type");
        if (!SessionHeader.TYPE.equals(type)) {
            throw SessionJson.invalid("header.type", "must be " + SessionHeader.TYPE);
        }
        var version = SessionJson.requireInt(object, "version");
        if (version != SessionHeader.VERSION) {
            throw SessionJson.invalid("header.version", "is unsupported: " + version);
        }
        return new SessionHeader(
                parseUuid(SessionJson.requireText(object, "id")),
                SessionJson.requireInstant(object, "timestamp"),
                Path.of(SessionJson.requireText(object, "cwd")));
    }

    byte[] encodeEntry(SessionEntry entry) throws JsonProcessingException {
        var object = mapper.createObjectNode();
        object.put("type", entry.type());
        object.put("id", entry.id());
        putNullable(object, "parentId", entry.parentId());
        object.put("timestamp", entry.timestamp().toString());
        switch (entry) {
            case SessionMessageEntry message ->
                    object.set("message", messages.encode(message.message().message()));
            case ModelChangeEntry model -> object.set("model", messages.encodeModel(model.model()));
            case ThinkingLevelChangeEntry thinking -> object.put(
                    "thinkingLevel", thinking.thinkingLevel().name().toLowerCase(Locale.ROOT));
            case SessionInfoEntry info -> putNullable(object, "name", info.name());
            case LabelEntry label -> {
                object.put("targetId", label.targetId());
                putNullable(object, "label", label.label());
            }
        }
        return mapper.writeValueAsBytes(object);
    }

    SessionEntry decodeEntry(byte[] json) throws IOException {
        var object = SessionJson.requireObject(mapper.readTree(json), "entry");
        var type = SessionJson.requireText(object, "type");
        var id = SessionJson.requireText(object, "id");
        var parentId = SessionJson.nullableText(object, "parentId");
        Instant timestamp = SessionJson.requireInstant(object, "timestamp");
        return switch (type) {
            case SessionMessageEntry.TYPE -> new SessionMessageEntry(
                    id,
                    parentId,
                    timestamp,
                    StandardAgentMessage.of(messages.decode(SessionJson.required(object, "message"))));
            case ModelChangeEntry.TYPE -> new ModelChangeEntry(
                    id,
                    parentId,
                    timestamp,
                    messages.decodeModel(SessionJson.required(object, "model"), "entry.model"));
            case ThinkingLevelChangeEntry.TYPE -> new ThinkingLevelChangeEntry(
                    id,
                    parentId,
                    timestamp,
                    parseThinkingLevel(SessionJson.requireText(object, "thinkingLevel")));
            case SessionInfoEntry.TYPE -> new SessionInfoEntry(
                    id, parentId, timestamp, SessionJson.nullableText(object, "name"));
            case LabelEntry.TYPE -> new LabelEntry(
                    id,
                    parentId,
                    timestamp,
                    SessionJson.requireText(object, "targetId"),
                    SessionJson.nullableText(object, "label"));
            default -> throw SessionJson.invalid("entry.type", "is unknown: " + type);
        };
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw SessionJson.invalid("header.id", "must be a UUID", e);
        }
    }

    private static ThinkingLevel parseThinkingLevel(String value) {
        try {
            return ThinkingLevel.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw SessionJson.invalid("entry.thinkingLevel", "has an unknown value", e);
        }
    }

    private static void putNullable(ObjectNode object, String field, String value) {
        if (value == null) {
            object.putNull(field);
        } else {
            object.put(field, value);
        }
    }
}
