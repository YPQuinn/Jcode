package site.pplee.jcode.codingagent;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.CostEstimate;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.ModelReplayState;
import site.pplee.jcode.ai.message.ResponseMetadata;
import site.pplee.jcode.ai.message.ModelFailureKind;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Explicit version-one JSON mapping for standard model messages. */
final class SessionMessageCodec {
    private final ObjectMapper mapper;

    SessionMessageCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode encode(Message message) {
        return switch (message) {
            case Message.User user -> encodeUser(user);
            case Message.Assistant assistant -> encodeAssistant(assistant);
            case Message.ToolResultMessage toolResult -> encodeToolResult(toolResult);
        };
    }

    Message decode(JsonNode node) {
        var object = SessionJson.requireObject(node, "message");
        return switch (SessionJson.requireText(object, "role")) {
            case "user" -> new Message.User(
                    decodeContents(SessionJson.requireArray(object, "content")),
                    SessionJson.requireInstant(object, "timestamp"));
            case "assistant" -> decodeAssistant(object);
            case "tool_result" -> new Message.ToolResultMessage(
                    SessionJson.requireText(object, "toolCallId"),
                    SessionJson.requireText(object, "toolName"),
                    decodeContents(SessionJson.requireArray(object, "content")),
                    SessionJson.requireBoolean(object, "error"),
                    SessionJson.requireInstant(object, "timestamp"));
            default -> throw SessionJson.invalid("message.role", "has an unknown value");
        };
    }

    ObjectNode encodeModel(ModelRef model) {
        var object = mapper.createObjectNode();
        object.put("provider", model.provider());
        object.put("api", model.api());
        object.put("modelId", model.modelId());
        return object;
    }

    ModelRef decodeModel(JsonNode node, String location) {
        var object = SessionJson.requireObject(node, location);
        return new ModelRef(
                SessionJson.requireText(object, "provider"),
                SessionJson.requireText(object, "api"),
                SessionJson.requireText(object, "modelId"));
    }

    private ObjectNode encodeUser(Message.User user) {
        var object = mapper.createObjectNode();
        object.put("role", "user");
        object.set("content", encodeContents(user.content()));
        object.put("timestamp", user.timestamp().toString());
        return object;
    }

    private ObjectNode encodeAssistant(Message.Assistant assistant) {
        var object = mapper.createObjectNode();
        object.put("role", "assistant");
        object.set("content", encodeContents(assistant.content()));
        object.put("stopReason", assistant.stopReason().name().toLowerCase(Locale.ROOT));
        putNullable(object, "errorMessage", assistant.errorMessage());
        object.set("usage", encodeUsage(assistant.usage()));
        object.put("timestamp", assistant.timestamp().toString());
        if (assistant.sourceModel() == null) {
            object.putNull("sourceModel");
        } else {
            object.set("sourceModel", encodeModel(assistant.sourceModel()));
        }
        object.set("metadata", encodeMetadata(assistant.metadata()));
        return object;
    }

    private Message.Assistant decodeAssistant(ObjectNode object) {
        var sourceModel = SessionJson.nullableObject(object, "sourceModel");
        return new Message.Assistant(
                decodeContents(SessionJson.requireArray(object, "content")),
                parseEnum(StopReason.class, SessionJson.requireText(object, "stopReason"),
                        "message.stopReason"),
                SessionJson.nullableText(object, "errorMessage"),
                decodeUsage(SessionJson.requireObject(object, "usage")),
                SessionJson.requireInstant(object, "timestamp"),
                sourceModel == null ? null : decodeModel(sourceModel, "message.sourceModel"),
                decodeMetadata(SessionJson.requireObject(object, "metadata")));
    }

    private ObjectNode encodeToolResult(Message.ToolResultMessage toolResult) {
        var object = mapper.createObjectNode();
        object.put("role", "tool_result");
        object.put("toolCallId", toolResult.toolCallId());
        object.put("toolName", toolResult.toolName());
        object.set("content", encodeContents(toolResult.content()));
        object.put("error", toolResult.error());
        object.put("timestamp", toolResult.timestamp().toString());
        return object;
    }

    ArrayNode encodeContents(java.util.List<Content> contents) {
        var array = mapper.createArrayNode();
        contents.forEach(content -> array.add(encodeContent(content)));
        return array;
    }

    java.util.List<Content> decodeContents(ArrayNode array) {
        var contents = new ArrayList<Content>(array.size());
        for (var node : array) {
            contents.add(decodeContent(node));
        }
        return List.copyOf(contents);
    }

    private ObjectNode encodeContent(Content content) {
        var object = mapper.createObjectNode();
        switch (content) {
            case Content.Text text -> {
                object.put("type", "text");
                object.put("text", text.text());
                putReplayState(object, text.replayState());
            }
            case Content.Thinking thinking -> {
                object.put("type", "thinking");
                object.put("text", thinking.text());
                putReplayState(object, thinking.replayState());
            }
            case Content.ToolCall call -> {
                object.put("type", "tool_call");
                object.put("id", call.id());
                object.put("name", call.name());
                object.set("arguments", call.arguments().deepCopy());
            }
            case Content.Image image -> {
                object.put("type", "image");
                object.put("mediaType", image.mediaType());
                object.put("base64Data", image.base64Data());
            }
        }
        return object;
    }

    private Content decodeContent(JsonNode node) {
        var object = SessionJson.requireObject(node, "content");
        return switch (SessionJson.requireText(object, "type")) {
            case "text" -> new Content.Text(
                    SessionJson.requireText(object, "text"), decodeReplayState(object));
            case "thinking" -> new Content.Thinking(
                    SessionJson.requireText(object, "text"), decodeReplayState(object));
            case "tool_call" -> new Content.ToolCall(
                    SessionJson.requireText(object, "id"),
                    SessionJson.requireText(object, "name"),
                    SessionJson.required(object, "arguments").deepCopy());
            case "image" -> new Content.Image(
                    SessionJson.requireText(object, "mediaType"),
                    SessionJson.requireText(object, "base64Data"));
            default -> throw SessionJson.invalid("content.type", "has an unknown value");
        };
    }

    private void putReplayState(ObjectNode object, ModelReplayState replayState) {
        if (replayState == null) {
            object.putNull("replayState");
            return;
        }
        var replay = mapper.createObjectNode();
        replay.put("format", replayState.format());
        replay.put("payload", replayState.payload());
        object.set("replayState", replay);
    }

    private ModelReplayState decodeReplayState(ObjectNode object) {
        var replay = SessionJson.nullableObject(object, "replayState");
        return replay == null ? null : new ModelReplayState(
                SessionJson.requireText(replay, "format"),
                SessionJson.requireText(replay, "payload"));
    }

    ObjectNode encodeUsage(Usage usage) {
        var object = mapper.createObjectNode();
        object.put("input", usage.input());
        object.put("output", usage.output());
        object.put("cacheRead", usage.cacheRead());
        object.put("cacheWrite", usage.cacheWrite());
        object.put("totalTokens", usage.totalTokens());
        object.put("reasoningTokens", usage.reasoningTokens());
        if (usage.cost().isEmpty()) {
            object.putNull("cost");
        } else {
            object.set("cost", encodeCost(usage.cost().orElseThrow()));
        }
        return object;
    }

    Usage decodeUsage(ObjectNode object) {
        var cost = SessionJson.nullableObject(object, "cost");
        return new Usage(
                SessionJson.requireLong(object, "input"),
                SessionJson.requireLong(object, "output"),
                SessionJson.requireLong(object, "cacheRead"),
                SessionJson.requireLong(object, "cacheWrite"),
                SessionJson.requireLong(object, "totalTokens"),
                SessionJson.requireLong(object, "reasoningTokens"),
                cost == null ? Optional.empty() : Optional.of(decodeCost(cost)));
    }

    private ObjectNode encodeCost(CostEstimate cost) {
        var object = mapper.createObjectNode();
        object.put("currency", cost.currency());
        object.put("input", cost.input().toPlainString());
        object.put("output", cost.output().toPlainString());
        object.put("cacheRead", cost.cacheRead().toPlainString());
        object.put("cacheWrite", cost.cacheWrite().toPlainString());
        object.put("total", cost.total().toPlainString());
        return object;
    }

    private CostEstimate decodeCost(ObjectNode object) {
        return new CostEstimate(
                SessionJson.requireText(object, "currency"),
                SessionJson.requireDecimal(object, "input"),
                SessionJson.requireDecimal(object, "output"),
                SessionJson.requireDecimal(object, "cacheRead"),
                SessionJson.requireDecimal(object, "cacheWrite"),
                SessionJson.requireDecimal(object, "total"));
    }

    private ObjectNode encodeMetadata(ResponseMetadata metadata) {
        var object = mapper.createObjectNode();
        putNullable(object, "responseId", metadata.responseId().orElse(null));
        putNullable(object, "providerRequestId", metadata.providerRequestId().orElse(null));
        putNullable(object, "rawTerminalReason", metadata.rawTerminalReason().orElse(null));
        putNullable(object, "failureKind", metadata.failureKind().map(Enum::name).orElse(null));
        return object;
    }

    private ResponseMetadata decodeMetadata(ObjectNode object) {
        String rawFailureKind = object.has("failureKind")
                ? SessionJson.nullableText(object, "failureKind") : null;
        ModelFailureKind failureKind = null;
        if (rawFailureKind != null) {
            try {
                failureKind = ModelFailureKind.valueOf(rawFailureKind.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Future classifications are intentionally treated as unknown.
            }
        }
        return ResponseMetadata.of(
                SessionJson.nullableText(object, "responseId"),
                SessionJson.nullableText(object, "providerRequestId"),
                SessionJson.nullableText(object, "rawTerminalReason"),
                failureKind);
    }

    private static void putNullable(ObjectNode object, String field, String value) {
        if (value == null) {
            object.putNull(field);
        } else {
            object.put(field, value);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String location) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw SessionJson.invalid(location, "has an unknown value", e);
        }
    }
}
