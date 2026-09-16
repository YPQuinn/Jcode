package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(targetModel, "targetModel must not be null");
        var items = new ArrayList<ObjectNode>();
        var pending = new LinkedHashMap<String, String>();
        var suppressed = new HashSet<String>();
        var normalizedIds = new HashMap<String, String>();
        var replayContext = new ReplayContext();
        boolean replayedReasoning = false;

        for (Message message : messages) {
            switch (message) {
                case Message.User user -> {
                    flushPending(items, pending);
                    appendUser(items, user);
                }
                case Message.Assistant assistant -> {
                    flushPending(items, pending);
                    if (assistant.stopReason() == StopReason.ERROR
                            || assistant.stopReason() == StopReason.ABORTED) {
                        suppressToolCalls(assistant, suppressed, normalizedIds);
                    } else if (appendAssistant(
                            items, assistant, targetModel, replayContext, pending, normalizedIds)) {
                        replayedReasoning = true;
                    }
                }
                case Message.ToolResultMessage toolResult ->
                        appendToolResult(items, toolResult, pending, suppressed, normalizedIds);
            }
        }
        flushPending(items, pending);
        return new OpenAiTranscriptPlan(items, replayedReasoning);
    }

    private void appendUser(List<ObjectNode> items, Message.User user) {
        String text = joinText(user.content());
        if (text.isEmpty()) {
            return;
        }
        ObjectNode item = objectMapper.createObjectNode();
        item.put("role", "user");
        item.putArray("content").addObject().put("type", "input_text").put("text", text);
        items.add(item);
    }

    private boolean appendAssistant(
            List<ObjectNode> items,
            Message.Assistant assistant,
            ModelRef targetModel,
            ReplayContext replayContext,
            Map<String, String> pending,
            Map<String, String> normalizedIds
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
                    NormalizedIds ids = normalizeId(toolCall.id(), normalizedIds);
                    pending.put(ids.callId(), toolCall.name());
                    items.add(functionCallItem(toolCall, ids, origin == Origin.SAME_MODEL && replayedReasoningThisTurn));
                }
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
        item.putArray("content").addObject().put("type", "output_text").put("text", text.text());
        return item;
    }

    private ObjectNode functionCallItem(Content.ToolCall toolCall, NormalizedIds ids, boolean keepItemId) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("type", "function_call");
        item.put("call_id", ids.callId());
        item.put("name", toolCall.name());
        item.put("arguments", toolCall.arguments().toString());
        if (keepItemId) {
            String itemId = OpenAiToolCallIds.validItemId(ids.itemId());
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
            Map<String, String> normalizedIds
    ) {
        String callId = normalizeId(toolResult.toolCallId(), normalizedIds).callId();
        if (suppressed.contains(callId) || !pending.containsKey(callId)) {
            return;
        }
        pending.remove(callId);
        ObjectNode item = objectMapper.createObjectNode();
        item.put("type", "function_call_output");
        item.put("call_id", callId);
        item.put("output", joinText(toolResult.content()));
        items.add(item);
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

    private void flushPending(List<ObjectNode> items, Map<String, String> pending) {
        for (String callId : pending.keySet()) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("type", "function_call_output");
            item.put("call_id", callId);
            item.put("output", "No result provided");
            items.add(item);
        }
        pending.clear();
    }

    private NormalizedIds normalizeId(String raw, Map<String, String> normalizedIds) {
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

    private static String joinText(List<Content> blocks) {
        var sb = new StringBuilder();
        for (Content block : blocks) {
            if (block instanceof Content.Text textBlock) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(textBlock.text());
            }
        }
        return sb.toString();
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
