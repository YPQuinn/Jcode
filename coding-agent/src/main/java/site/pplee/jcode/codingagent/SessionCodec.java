package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.session.LabelEntry;
import site.pplee.jcode.codingagent.session.ModelChangeEntry;
import site.pplee.jcode.codingagent.session.SessionEntry;
import site.pplee.jcode.codingagent.session.SessionHeader;
import site.pplee.jcode.codingagent.session.SessionInfoEntry;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;
import site.pplee.jcode.codingagent.session.ThinkingLevelChangeEntry;
import site.pplee.jcode.codingagent.session.CompactionEntry;
import site.pplee.jcode.codingagent.session.BranchSummaryEntry;
import site.pplee.jcode.codingagent.session.CustomEntry;
import site.pplee.jcode.codingagent.session.CustomMessageEntry;
import site.pplee.jcode.codingagent.session.SummaryDetails;
import site.pplee.jcode.codingagent.session.TokenEstimateSource;

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
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
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
            case CompactionEntry compaction -> {
                object.put("summary", compaction.summary());
                object.put("firstKeptEntryId", compaction.firstKeptEntryId());
                object.put("tokensBefore", compaction.tokensBefore());
                object.put("tokenEstimateSource", compaction.tokenEstimateSource().name().toLowerCase(Locale.ROOT));
                object.set("summaryModel", messages.encodeModel(compaction.summaryModel()));
                object.set("usage", messages.encodeUsage(compaction.usage()));
                object.set("details", encodeDetails(compaction.details()));
            }
            case BranchSummaryEntry summary -> {
                object.put("fromId", summary.fromId());
                object.put("summary", summary.summary());
                object.set("summaryModel", messages.encodeModel(summary.summaryModel()));
                object.set("usage", messages.encodeUsage(summary.usage()));
                object.set("details", encodeDetails(summary.details()));
            }
            case CustomEntry custom -> {
                object.put("extensionId", custom.extensionId());
                object.put("customType", custom.customType());
                if (custom.data() == null) {
                    object.putNull("data");
                } else {
                    object.set("data", custom.data());
                }
            }
            case CustomMessageEntry custom -> {
                var message = custom.message();
                object.put("extensionId", message.extensionId());
                object.put("customType", message.customType());
                object.set("content", messages.encodeContents(message.content()));
                if (message.details() == null) {
                    object.putNull("details");
                } else {
                    object.set("details", message.details());
                }
                object.put("display", message.display());
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
            case CompactionEntry.TYPE -> new CompactionEntry(
                    id,
                    parentId,
                    timestamp,
                    SessionJson.requireText(object, "summary"),
                    SessionJson.requireText(object, "firstKeptEntryId"),
                    SessionJson.requireLong(object, "tokensBefore"),
                    parseEstimateSource(SessionJson.requireText(object, "tokenEstimateSource")),
                    messages.decodeModel(SessionJson.required(object, "summaryModel"), "entry.summaryModel"),
                    messages.decodeUsage(SessionJson.requireObject(object, "usage")),
                    decodeDetails(SessionJson.requireObject(object, "details")));
            case BranchSummaryEntry.TYPE -> new BranchSummaryEntry(
                    id,
                    parentId,
                    timestamp,
                    SessionJson.requireText(object, "fromId"),
                    SessionJson.requireText(object, "summary"),
                    messages.decodeModel(SessionJson.required(object, "summaryModel"), "entry.summaryModel"),
                    messages.decodeUsage(SessionJson.requireObject(object, "usage")),
                    decodeDetails(SessionJson.requireObject(object, "details")));
            case CustomEntry.TYPE -> new CustomEntry(
                    id, parentId, timestamp,
                    SessionJson.requireText(object, "extensionId"),
                    SessionJson.requireText(object, "customType"),
                    nullableTree(object, "data"));
            case CustomMessageEntry.TYPE -> new CustomMessageEntry(
                    id, parentId, timestamp,
                    SessionJson.requireText(object, "extensionId"),
                    SessionJson.requireText(object, "customType"),
                    messages.decodeContents(SessionJson.requireArray(object, "content")),
                    nullableTree(object, "details"),
                    SessionJson.requireBoolean(object, "display"));
            default -> throw SessionJson.invalid("entry.type", "is unknown: " + type);
        };
    }

    private static com.fasterxml.jackson.databind.JsonNode nullableTree(ObjectNode object, String field) {
        var value = object.get(field);
        if (value == null) {
            throw SessionJson.invalid("entry." + field, "is required");
        }
        return value.isNull() ? null : value.deepCopy();
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

    private ObjectNode encodeDetails(SummaryDetails details) {
        var object = mapper.createObjectNode();
        var read = object.putArray("readFiles");
        details.readFiles().forEach(read::add);
        var modified = object.putArray("modifiedFiles");
        details.modifiedFiles().forEach(modified::add);
        return object;
    }

    private static SummaryDetails decodeDetails(ObjectNode object) {
        return new SummaryDetails(
                SessionJson.requireStringArray(object, "readFiles"),
                SessionJson.requireStringArray(object, "modifiedFiles"));
    }

    private static TokenEstimateSource parseEstimateSource(String value) {
        try {
            return TokenEstimateSource.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw SessionJson.invalid("entry.tokenEstimateSource", "has an unknown value", e);
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
