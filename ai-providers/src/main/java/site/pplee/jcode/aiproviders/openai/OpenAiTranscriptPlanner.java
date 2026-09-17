package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.ModelRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Request-local OpenAI Responses transcript planner. Walks a projected
 * message list without mutating it, repairs tool-call pairing, and decides
 * which same-model replay state is safe to send.
 */
final class OpenAiTranscriptPlanner {
    static final String NO_TOOL_OUTPUT = "(no tool output)";

    private final ObjectMapper objectMapper;

    OpenAiTranscriptPlanner(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    record OpenAiTranscriptPlan(List<ObjectNode> inputItems, boolean replayedReasoning) {
        OpenAiTranscriptPlan {
            inputItems = List.copyOf(Objects.requireNonNull(inputItems, "inputItems must not be null"));
        }
    }

    OpenAiTranscriptPlan plan(List<Message> messages, ModelRef targetModel) {
        return plan(messages, targetModel, false, Map.of());
    }

    OpenAiTranscriptPlan plan(List<Message> messages, ModelRef targetModel, boolean imageInput) {
        return plan(messages, targetModel, imageInput, Map.of());
    }

    OpenAiTranscriptPlan plan(
            List<Message> messages,
            ModelRef targetModel,
            boolean imageInput,
            Map<String, String> grammarToolInputProperties
    ) {
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(targetModel, "targetModel must not be null");
        Map<String, String> grammarProperties = Map.copyOf(Objects.requireNonNull(
                grammarToolInputProperties, "grammarToolInputProperties must not be null"));
        var items = new ArrayList<ObjectNode>();
        var pending = new LinkedHashMap<String, String>();
        var suppressed = new HashSet<String>();
        var normalizedIds = new HashMap<String, String>();
        var replayContext = new ReplayContext();
        boolean replayedReasoning = false;

        for (Message message : messages) {
            switch (message) {
                case Message.User user -> {
                    flushPending(items, pending, grammarProperties);
                    appendUser(items, user, imageInput);
                }
                case Message.Assistant assistant -> {
                    flushPending(items, pending, grammarProperties);
                    if (assistant.stopReason() == StopReason.ERROR
                            || assistant.stopReason() == StopReason.ABORTED) {
                        suppressToolCalls(assistant, suppressed, normalizedIds);
                    } else if (appendAssistant(
                            items,
                            assistant,
                            targetModel,
                            replayContext,
                            pending,
                            normalizedIds,
                            grammarProperties)) {
                        replayedReasoning = true;
                    }
                }
                case Message.ToolResultMessage toolResult ->
                        appendToolResult(
                                items,
                                toolResult,
                                pending,
                                suppressed,
                                normalizedIds,
                                imageInput,
                                grammarProperties);
            }
        }
        flushPending(items, pending, grammarProperties);
        return new OpenAiTranscriptPlan(items, replayedReasoning);
    }

    private void appendUser(List<ObjectNode> items, Message.User user, boolean imageInput) {
        ArrayNode content = objectMapper.createArrayNode();
        StringBuilder pendingText = new StringBuilder();
        for (Content block : user.content()) {
            switch (block) {
                case Content.Text text -> appendAdjacentText(pendingText, OpenAiRequestUnicode.sanitizeText(text.text()));
                case Content.Image image -> {
                    if (imageInput) {
                        flushAdjacentText(content, pendingText);
                        content.add(inputImage(image));
                    } else {
                        appendAdjacentText(pendingText, omittedImage(image.mediaType()));
                    }
                }
                case Content.Thinking ignored -> throw new IllegalArgumentException(
                        "user message cannot contain thinking content");
                case Content.ToolCall ignored -> throw new IllegalArgumentException(
                        "user message cannot contain tool-call content");
            }
        }
        flushAdjacentText(content, pendingText);
        if (content.isEmpty()) {
            return;
        }
        ObjectNode item = objectMapper.createObjectNode();
        item.put("role", "user");
        item.set("content", content);
        items.add(item);
    }

    private boolean appendAssistant(
            List<ObjectNode> items,
            Message.Assistant assistant,
            ModelRef targetModel,
            ReplayContext replayContext,
            Map<String, String> pending,
            Map<String, String> normalizedIds,
            Map<String, String> grammarProperties
    ) {
        Origin origin = classify(assistant.sourceModel(), targetModel);
        boolean replayedReasoningThisTurn = false;
        for (Content block : assistant.content()) {
            switch (block) {
                case Content.Thinking thinking -> {
                    if (origin == Origin.SAME_MODEL
                            && appendReasoning(items, thinking)) {
                        replayedReasoningThisTurn = true;
                    }
                }
                case Content.Text text -> items.add(messageItem(text, origin, replayContext));
                case Content.ToolCall toolCall -> {
                    OpenAiRequestUnicode.requireWellFormedIdentity(toolCall.name(), "tool name");
                    NormalizedIds ids = normalizeId(toolCall.id(), normalizedIds);
                    pending.put(ids.callId(), toolCall.name());
                    items.add(toolCallItem(
                            toolCall,
                            ids,
                            origin == Origin.SAME_MODEL && replayedReasoningThisTurn,
                            grammarProperties.get(toolCall.name())));
                }
                case Content.Image ignored -> throw new IllegalArgumentException(
                        "assistant image content is not supported");
            }
        }
        return replayedReasoningThisTurn;
    }

    private boolean appendReasoning(List<ObjectNode> items, Content.Thinking thinking) {
        return OpenAiReplayStateCodec.decodeReasoning(thinking.replayState(), thinking.text())
                .filter(OpenAiReplayStateCodec::hasEncryptedContent)
                .map(item -> {
                    items.add((ObjectNode) item);
                    return true;
                })
                .orElse(false);
    }

    private ObjectNode messageItem(Content.Text text, Origin origin, ReplayContext replayContext) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("type", "message");
        item.put("role", "assistant");
        item.put("status", "completed");
        if (origin == Origin.SAME_MODEL) {
            var replay = OpenAiReplayStateCodec.decodeMessage(text.replayState(), text.text());
            if (replay.isPresent()) {
                OpenAiRequestUnicode.requireWellFormedIdentity(replay.get().itemId(), "replay item id");
                if (replay.get().phase() != null) {
                    OpenAiRequestUnicode.requireWellFormedIdentity(replay.get().phase(), "replay phase");
                }
                item.put("id", replay.get().itemId());
                if (replay.get().phase() != null) {
                    item.put("phase", replay.get().phase());
                }
            } else {
                item.put("id", replayContext.nextAssistantItemId());
            }
        } else {
            item.put("id", replayContext.nextAssistantItemId());
        }
        item.putArray("content").addObject()
                .put("type", "output_text")
                .put("text", OpenAiRequestUnicode.sanitizeText(text.text()));
        return item;
    }

    private ObjectNode toolCallItem(
            Content.ToolCall toolCall,
            NormalizedIds ids,
            boolean keepItemId,
            String grammarInputProperty
    ) {
        if (grammarInputProperty != null) {
            return customToolCallItem(toolCall, ids, keepItemId, grammarInputProperty);
        }
        return functionCallItem(toolCall, ids, keepItemId);
    }

    private ObjectNode functionCallItem(Content.ToolCall toolCall, NormalizedIds ids, boolean keepItemId) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("type", "function_call");
        item.put("call_id", ids.callId());
        item.put("name", toolCall.name());
        item.put("arguments", OpenAiRequestUnicode.copySanitized(objectMapper, toolCall.arguments()).toString());
        if (keepItemId) {
            String itemId = OpenAiToolCallIds.validFunctionItemId(ids.itemId());
            if (itemId != null) {
                item.put("id", itemId);
            }
        }
        return item;
    }

    private ObjectNode customToolCallItem(
            Content.ToolCall toolCall,
            NormalizedIds ids,
            boolean keepItemId,
            String grammarInputProperty
    ) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("type", "custom_tool_call");
        item.put("call_id", ids.callId());
        item.put("name", toolCall.name());
        item.put("input", OpenAiRequestUnicode.sanitizeText(OpenAiConstrainedSampling.grammarToolInput(
                toolCall.name(), toolCall.arguments(), grammarInputProperty)));
        if (keepItemId) {
            String itemId = OpenAiToolCallIds.validCustomItemId(ids.itemId());
            if (itemId != null) {
                item.put("id", itemId);
            }
        }
        return item;
    }

    private void appendToolResult(
            List<ObjectNode> items,
            Message.ToolResultMessage toolResult,
            Map<String, String> pending,
            Set<String> suppressed,
            Map<String, String> normalizedIds,
            boolean imageInput,
            Map<String, String> grammarProperties
    ) {
        String callId = normalizeId(toolResult.toolCallId(), normalizedIds).callId();
        if (suppressed.contains(callId) || !pending.containsKey(callId)) {
            return;
        }
        String toolName = pending.remove(callId);
        ObjectNode item = objectMapper.createObjectNode();
        item.put("type", grammarProperties.containsKey(toolName)
                ? "custom_tool_call_output"
                : "function_call_output");
        item.put("call_id", callId);
        writeToolResultOutput(item, toolResult.content(), imageInput);
        items.add(item);
    }

    private void writeToolResultOutput(ObjectNode item, List<Content> blocks, boolean imageInput) {
        ArrayNode content = objectMapper.createArrayNode();
        StringBuilder pendingText = new StringBuilder();
        boolean hasImage = false;
        for (Content block : blocks) {
            switch (block) {
                case Content.Text text -> appendAdjacentText(pendingText, OpenAiRequestUnicode.sanitizeText(text.text()));
                case Content.Image image -> {
                    hasImage = true;
                    if (imageInput) {
                        flushVisibleAdjacentText(content, pendingText);
                        content.add(inputImage(image));
                    } else {
                        appendAdjacentText(pendingText, omittedImage(image.mediaType()));
                    }
                }
                case Content.Thinking ignored -> throw new IllegalArgumentException(
                        "tool result cannot contain thinking content");
                case Content.ToolCall ignored -> throw new IllegalArgumentException(
                        "tool result cannot contain tool-call content");
            }
        }
        flushVisibleAdjacentText(content, pendingText);
        if (!imageInput || !hasImage) {
            item.put("output", visibleToolText(content));
            return;
        }
        item.set("output", content);
    }

    private static String visibleToolText(ArrayNode content) {
        if (content.isEmpty()) {
            return NO_TOOL_OUTPUT;
        }
        var sb = new StringBuilder();
        for (int i = 0; i < content.size(); i++) {
            String text = content.get(i).path("text").asText("");
            if (text.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(text);
        }
        return sb.isEmpty() ? NO_TOOL_OUTPUT : sb.toString();
    }

    private ObjectNode inputImage(Content.Image image) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("type", "input_image");
        item.put("detail", "auto");
        item.put("image_url", "data:" + image.mediaType() + ";base64," + image.base64Data());
        return item;
    }

    static String omittedImage(String mediaType) {
        return "[Image omitted: " + mediaType + "]";
    }

    private static void appendAdjacentText(StringBuilder pending, String text) {
        if (pending.isEmpty()) {
            pending.append(text);
            return;
        }
        pending.append('\n').append(text);
    }

    private void flushAdjacentText(ArrayNode content, StringBuilder pending) {
        flushAdjacentText(content, pending, false);
    }

    /**
     * Tool-result flush: drop whitespace-only text so a vision
     * {@code function_call_output} array never contains a blank
     * {@code input_text}. Visible text is unchanged.
     */
    private void flushVisibleAdjacentText(ArrayNode content, StringBuilder pending) {
        flushAdjacentText(content, pending, true);
    }

    private void flushAdjacentText(ArrayNode content, StringBuilder pending, boolean omitBlank) {
        if (pending.isEmpty()) {
            return;
        }
        String text = pending.toString();
        pending.setLength(0);
        if (omitBlank && text.isBlank()) {
            return;
        }
        content.addObject().put("type", "input_text").put("text", text);
    }

    private void suppressToolCalls(
            Message.Assistant assistant,
            Set<String> suppressed,
            Map<String, String> normalizedIds
    ) {
        for (Content block : assistant.content()) {
            if (block instanceof Content.ToolCall toolCall) {
                suppressed.add(normalizeId(toolCall.id(), normalizedIds).callId());
            }
        }
    }

    private void flushPending(
            List<ObjectNode> items,
            Map<String, String> pending,
            Map<String, String> grammarProperties
    ) {
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("type", grammarProperties.containsKey(entry.getValue())
                    ? "custom_tool_call_output"
                    : "function_call_output");
            item.put("call_id", entry.getKey());
            item.put("output", "No result provided");
            items.add(item);
        }
        pending.clear();
    }

    private NormalizedIds normalizeId(String raw, Map<String, String> normalizedIds) {
        OpenAiRequestUnicode.requireWellFormedIdentity(raw, "tool-call id");
        String cached = normalizedIds.get(raw);
        if (cached != null) {
            return new NormalizedIds(cached, itemIdOf(raw));
        }
        NormalizedIds ids = decodeOrHash(raw);
        normalizedIds.put(raw, ids.callId());
        return ids;
    }

    private static NormalizedIds decodeOrHash(String raw) {
        if (OpenAiToolCallIds.isEncoded(raw)) {
            var decoded = OpenAiToolCallIds.decode(raw).orElseThrow();
            return new NormalizedIds(decoded.callId(), decoded.itemId());
        }
        if (OpenAiToolCallIds.isSafeId(raw)) {
            return new NormalizedIds(raw, null);
        }
        return new NormalizedIds(OpenAiToolCallIds.hashedForeignCallId(raw), null);
    }

    private static String itemIdOf(String raw) {
        if (!OpenAiToolCallIds.isEncoded(raw)) {
            return null;
        }
        return OpenAiToolCallIds.itemId(raw);
    }

    private static Origin classify(ModelRef source, ModelRef target) {
        if (source == null) {
            return Origin.FOREIGN_OR_UNKNOWN;
        }
        if (source.equals(target)) {
            return Origin.SAME_MODEL;
        }
        if (source.provider().equals(target.provider()) && source.api().equals(target.api())) {
            return Origin.SAME_PROVIDER_DIFFERENT_MODEL;
        }
        return Origin.FOREIGN_OR_UNKNOWN;
    }

    private enum Origin {
        SAME_MODEL,
        SAME_PROVIDER_DIFFERENT_MODEL,
        FOREIGN_OR_UNKNOWN
    }

    private record NormalizedIds(String callId, String itemId) {
    }

    private static final class ReplayContext {
        private int assistantItemIndex;

        String nextAssistantItemId() {
            return "msg_jcode_" + (assistantItemIndex++);
        }
    }
}
