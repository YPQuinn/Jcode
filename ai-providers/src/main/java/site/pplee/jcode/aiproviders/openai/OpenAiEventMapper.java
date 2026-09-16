package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.ModelReplayState;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Maps OpenAI Responses API stream events to {@link AssistantMessageEvent}s.
 * Uses a lifecycle-phase dispatcher and polymorphic {@link Slot}s keyed by
 * {@code output_index} to track text, thinking, and tool-call blocks. The
 * accumulated content list is kept in sync so partial messages carry current
 * block values. Terminal events ({@code response.completed},
 * {@code response.incomplete}, {@code response.failed}, {@code error}) return
 * the final {@code Done}/{@code Error} event exactly once.
 * {@code response.incomplete} maps {@code max_output_tokens} to {@code Done(LENGTH)}
 * and every other or missing incomplete reason to {@code Error(ERROR)}.
 */
final class OpenAiEventMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode EMPTY_OBJECT = MAPPER.createObjectNode();

    private final ModelRef sourceModel;
    private final List<Content> content = new ArrayList<>();
    private final Map<Integer, Slot> slots = new HashMap<>();
    private final Set<Integer> completedIndexes = new HashSet<>();
    private final Map<String, Integer> reasoningIndexById = new HashMap<>();
    private Usage usage = Usage.zero();
    private boolean sawTerminal;
    private boolean toolCallSeen;

    OpenAiEventMapper(ModelRef sourceModel) {
        this.sourceModel = Objects.requireNonNull(sourceModel, "sourceModel must not be null");
    }

    /** Process one provider event; returns the Jcode events to push (possibly empty). */
    List<AssistantMessageEvent> onEvent(String eventName, JsonNode data) {
        return switch (eventName) {
            case "response.output_item.added" -> handleItemAdded(data);
            case "response.output_text.delta",
                 "response.refusal.delta",
                 "response.reasoning_text.delta",
                 "response.reasoning_summary_text.delta",
                 "response.function_call_arguments.delta" -> handleDelta(eventName, data);
            case "response.reasoning_summary_part.done" -> handleSummaryPartDone(data);
            case "response.function_call_arguments.done" -> handleArgumentsDone(data);
            case "response.output_item.done" -> handleItemDone(data);
            case "response.completed",
                 "response.incomplete",
                 "response.failed",
                 "error" -> handleTerminal(eventName, data);
            default -> List.of();
        };
    }

    private List<AssistantMessageEvent> handleItemAdded(JsonNode data) {
        var item = data.get("item");
        int index = data.path("output_index").asInt(-1);
        if (item == null || !item.isObject() || index < 0) {
            return List.of();
        }
        Slot existing = slots.get(index);
        if (existing != null) {
            if (!existing.matches(item)) {
                throw new IllegalStateException("output item type conflict at index " + index);
            }
            return List.of();
        }
        if (completedIndexes.contains(index)) {
            return List.of();
        }
        Slot slot = openSlot(index, item);
        return slot == null ? List.of() : List.of(slot.createStartEvent());
    }

    private Slot openSlot(int outputIndex, JsonNode item) {
        Slot slot = createSlot(item, content.size());
        if (slot == null) {
            return null;
        }
        content.add(slot.initialContent());
        slots.put(outputIndex, slot);
        if (slot instanceof ToolCallSlot) {
            toolCallSeen = true;
        }
        return slot;
    }

    private Slot createSlot(JsonNode item, int contentIndex) {
        String type = item.path("type").asText("");
        return switch (type) {
            case "message" -> new TextSlot(contentIndex);
            case "reasoning" -> {
                var slot = new ThinkingSlot(contentIndex);
                String id = item.path("id").asText("");
                if (!id.isBlank()) {
                    reasoningIndexById.put(id, contentIndex);
                }
                yield slot;
            }
            case "function_call" -> {
                String callId = item.path("call_id").asText("");
                String itemId = item.path("id").isTextual() ? item.path("id").asText() : null;
                String name = item.path("name").asText("");
                String encoded = OpenAiToolCallIds.encode(callId, itemId);
                String rawArguments = item.path("arguments").asText("");
                JsonNode arguments = OpenAiPartialJsonParser.parse(rawArguments, EMPTY_OBJECT);
                yield new ToolCallSlot(contentIndex, encoded, name, arguments, rawArguments);
            }
            default -> null;
        };
    }

    private List<AssistantMessageEvent> handleDelta(String eventName, JsonNode data) {
        int index = data.path("output_index").asInt(-1);
        if (index < 0) {
            return List.of();
        }
        Slot slot = slots.get(index);
        if (slot == null) {
            return List.of();
        }
        String delta = data.path("delta").asText("");
        return switch (eventName) {
            case "response.output_text.delta", "response.refusal.delta" ->
                    slot instanceof TextSlot textSlot ? List.of(textSlot.onDelta(delta)) : List.of();
            case "response.reasoning_text.delta", "response.reasoning_summary_text.delta" ->
                    slot instanceof ThinkingSlot thinkingSlot ? List.of(thinkingSlot.onDelta(delta)) : List.of();
            case "response.function_call_arguments.delta" ->
                    slot instanceof ToolCallSlot toolSlot ? List.of(toolSlot.onDelta(delta)) : List.of();
            default -> List.of();
        };
    }

    private List<AssistantMessageEvent> handleSummaryPartDone(JsonNode data) {
        int index = data.path("output_index").asInt(-1);
        if (index < 0) {
            return List.of();
        }
        if (slots.get(index) instanceof ThinkingSlot thinkingSlot) {
            return thinkingSlot.onSummaryPartDone();
        }
        return List.of();
    }

    private List<AssistantMessageEvent> handleArgumentsDone(JsonNode data) {
        int index = data.path("output_index").asInt(-1);
        if (index < 0) {
            return List.of();
        }
        if (slots.get(index) instanceof ToolCallSlot toolSlot) {
            String arguments = data.path("arguments").asText("");
            return toolSlot.onArgumentsDone(arguments);
        }
        return List.of();
    }

    private List<AssistantMessageEvent> handleItemDone(JsonNode data) {
        var item = data.get("item");
        int index = data.path("output_index").asInt(-1);
        if (item == null || !item.isObject() || index < 0) {
            return List.of();
        }
        if (completedIndexes.contains(index)) {
            return List.of();
        }
        Slot slot = slots.get(index);
        if (slot == null) {
            slot = openSlot(index, item);
            if (slot == null) {
                return List.of();
            }
            AssistantMessageEvent start = slot.createStartEvent();
            AssistantMessageEvent end = finalizeSlot(index, slot, item);
            return end == null ? List.of(start) : List.of(start, end);
        }
        if (!slot.matches(item)) {
            throw new IllegalStateException("output item type conflict at index " + index);
        }
        AssistantMessageEvent end = finalizeSlot(index, slot, item);
        return end == null ? List.of() : List.of(end);
    }

    private AssistantMessageEvent finalizeSlot(int index, Slot slot, JsonNode item) {
        slots.remove(index);
        completedIndexes.add(index);
        return slot.onDone(item);
    }

    private List<AssistantMessageEvent> handleTerminal(String eventName, JsonNode data) {
        sawTerminal = true;
        return switch (eventName) {
            case "response.completed" -> {
                usage = mapUsage(data.get("response"));
                backfillEncryptedReasoning(data.get("response"));
                StopReason reason = toolCallSeen ? StopReason.TOOL_CALL : StopReason.STOP;
                yield List.of(new AssistantMessageEvent.Done(reason, finalMessage(reason)));
            }
            case "response.incomplete" -> {
                usage = mapUsage(data.get("response"));
                backfillEncryptedReasoning(data.get("response"));
                yield List.of(mapIncomplete(data.get("response")));
            }
            case "response.failed" -> {
                usage = mapUsage(data.get("response"));
                backfillEncryptedReasoning(data.get("response"));
                var error = data.path("response").path("error");
                String message = error.isMissingNode() || !error.isObject()
                        ? "provider response failed"
                        : error.path("code").asText("unknown") + ": " + error.path("message").asText("no message");
                yield List.of(new AssistantMessageEvent.Error(StopReason.ERROR, errorMessage(message)));
            }
            case "error" -> {
                String message = data.path("code").asText("unknown")
                        + ": " + data.path("message").asText("no message");
                yield List.of(new AssistantMessageEvent.Error(StopReason.ERROR, errorMessage(message)));
            }
            default -> List.of();
        };
    }

    private AssistantMessageEvent mapIncomplete(JsonNode response) {
        String reason = incompleteReason(response);
        if ("max_output_tokens".equals(reason)) {
            return new AssistantMessageEvent.Done(StopReason.LENGTH, finalMessage(StopReason.LENGTH));
        }
        String message = reason == null
                ? "response incomplete: provider did not report a reason"
                : "response incomplete: " + reason;
        return new AssistantMessageEvent.Error(StopReason.ERROR, errorMessage(message));
    }

    private void backfillEncryptedReasoning(JsonNode response) {
        if (response == null || !response.isObject()) {
            return;
        }
        JsonNode output = response.get("output");
        if (output == null || !output.isArray()) {
            return;
        }
        for (JsonNode item : output) {
            if (!"reasoning".equals(item.path("type").asText(""))) {
                continue;
            }
            JsonNode encrypted = item.get("encrypted_content");
            if (encrypted == null || !encrypted.isTextual() || encrypted.asText().isBlank()) {
                continue;
            }
            Integer index = indexForReasoningItem(item);
            if (index == null || index < 0 || index >= content.size()) {
                continue;
            }
            if (!(content.get(index) instanceof Content.Thinking thinking)) {
                continue;
            }
            var stored = OpenAiReplayStateCodec.decodeReasoning(thinking.replayState(), thinking.text());
            if (stored.isPresent() && OpenAiReplayStateCodec.hasEncryptedContent(stored.get())) {
                continue;
            }
            var merged = stored.isPresent()
                    ? stored.get().deepCopy()
                    : item.deepCopy();
            if (merged instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                if (!OpenAiReplayStateCodec.hasEncryptedContent(object)) {
                    object.put("encrypted_content", encrypted.asText());
                }
                content.set(index, new Content.Thinking(
                        thinking.text(),
                        OpenAiReplayStateCodec.encodeReasoning(thinking.text(), object)));
            }
        }
    }

    private Integer indexForReasoningItem(JsonNode item) {
        String id = item.path("id").asText("");
        if (!id.isBlank() && reasoningIndexById.containsKey(id)) {
            return reasoningIndexById.get(id);
        }
        for (int i = 0; i < content.size(); i++) {
            if (content.get(i) instanceof Content.Thinking thinking
                    && !hasEncryptedReplay(thinking)) {
                if (!id.isBlank()) {
                    reasoningIndexById.put(id, i);
                }
                return i;
            }
        }
        return null;
    }

    private static boolean hasEncryptedReplay(Content.Thinking thinking) {
        return OpenAiReplayStateCodec.decodeReasoning(thinking.replayState(), thinking.text())
                .filter(OpenAiReplayStateCodec::hasEncryptedContent)
                .isPresent();
    }

    private static String incompleteReason(JsonNode response) {
        if (response == null || !response.isObject()) {
            return null;
        }
        JsonNode reason = response.path("incomplete_details").path("reason");
        if (!reason.isTextual()) {
            return null;
        }
        String value = reason.asText();
        return value.isBlank() ? null : value;
    }

    /** True once a terminal provider event has been seen. */
    boolean terminalHandled() {
        return sawTerminal;
    }

    /** Immutable copy of the accumulated content. */
    List<Content> content() {
        return List.copyOf(content);
    }

    private static String canonicalMessageText(JsonNode array) {
        if (array == null || !array.isArray()) {
            return null;
        }
        var sb = new StringBuilder();
        boolean any = false;
        for (JsonNode block : array) {
            String type = block.path("type").asText("");
            String value = switch (type) {
                case "output_text" -> block.has("text") ? block.path("text").asText("") : null;
                case "refusal" -> block.has("refusal")
                        ? block.path("refusal").asText("")
                        : (block.has("text") ? block.path("text").asText("") : null);
                default -> null;
            };
            if (value != null) {
                sb.append(value);
                any = true;
            }
        }
        return any ? sb.toString() : null;
    }

    private static String canonicalThinkingText(JsonNode item, String accumulated) {
        String summary = joinTypedText(item.get("summary"), "summary_text", "text", "\n\n");
        if (summary != null) {
            return summary;
        }
        String body = joinTypedText(item.get("content"), "reasoning_text", "text", "");
        return body != null ? body : accumulated;
    }

    private static String joinTypedText(JsonNode array, String type, String field, String separator) {
        if (array == null || !array.isArray()) {
            return null;
        }
        var sb = new StringBuilder();
        boolean any = false;
        for (JsonNode block : array) {
            if (!type.equals(block.path("type").asText(""))) {
                continue;
            }
            if (!block.has(field)) {
                continue;
            }
            if (any && !separator.isEmpty()) {
                sb.append(separator);
            }
            sb.append(block.path(field).asText(""));
            any = true;
        }
        return any ? sb.toString() : null;
    }

    private static JsonNode parseArguments(String raw) {
        JsonNode parsed = tryParse(raw);
        return parsed == null ? EMPTY_OBJECT : parsed;
    }

    private static JsonNode tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Usage mapUsage(JsonNode response) {
        if (response == null || !response.has("usage")) {
            return Usage.zero();
        }
        var usage = response.get("usage");
        long inputTokens = usage.path("input_tokens").asLong(0);
        long cached = usage.path("input_tokens_details").path("cached_tokens").asLong(0);
        long cacheWrite = usage.path("input_tokens_details").path("cache_write_tokens").asLong(0);
        long input = Math.max(0, inputTokens - cached - cacheWrite);
        long output = usage.path("output_tokens").asLong(0);
        long total = usage.path("total_tokens").asLong(0);
        if (total <= 0) {
            total = input + output + cached + cacheWrite;
        }
        return new Usage(input, output, cached, cacheWrite, total);
    }

    private Message.Assistant partial() {
        return new Message.Assistant(List.copyOf(content), StopReason.STOP, null, Usage.zero(), Instant.now(), sourceModel);
    }

    private Message.Assistant finalMessage(StopReason reason) {
        return new Message.Assistant(List.copyOf(content), reason, null, usage, Instant.now(), sourceModel);
    }

    private Message.Assistant errorMessage(String message) {
        return new Message.Assistant(List.copyOf(content), StopReason.ERROR, message, usage, Instant.now(), sourceModel);
    }

    private abstract static class Slot {
        final int contentIndex;

        Slot(int contentIndex) {
            this.contentIndex = contentIndex;
        }

        abstract Content initialContent();

        abstract AssistantMessageEvent createStartEvent();

        abstract AssistantMessageEvent onDone(JsonNode item);

        abstract boolean matches(JsonNode item);
    }

    private final class TextSlot extends Slot {
        final StringBuilder text = new StringBuilder();

        TextSlot(int contentIndex) {
            super(contentIndex);
        }

        @Override
        Content initialContent() {
            return new Content.Text("");
        }

        @Override
        AssistantMessageEvent createStartEvent() {
            return new AssistantMessageEvent.TextStart(contentIndex, partial());
        }

        AssistantMessageEvent onDelta(String delta) {
            text.append(delta);
            content.set(contentIndex, new Content.Text(text.toString()));
            return new AssistantMessageEvent.TextDelta(contentIndex, delta, partial());
        }

        @Override
        AssistantMessageEvent onDone(JsonNode item) {
            if (!"message".equals(item.path("type").asText(""))) {
                return null;
            }
            String confirmed = canonicalMessageText(item.get("content"));
            if (confirmed == null) {
                confirmed = text.toString();
            }
            text.setLength(0);
            text.append(confirmed);
            String itemId = item.path("id").isTextual() ? item.path("id").asText() : null;
            String phase = item.path("phase").isTextual() ? item.path("phase").asText() : null;
            ModelReplayState replayState = OpenAiReplayStateCodec.encodeMessage(confirmed, itemId, phase);
            content.set(contentIndex, new Content.Text(confirmed, replayState));
            return new AssistantMessageEvent.TextEnd(contentIndex, confirmed, partial());
        }

        @Override
        boolean matches(JsonNode item) {
            return "message".equals(item.path("type").asText(""));
        }
    }

    private final class ThinkingSlot extends Slot {
        final StringBuilder text = new StringBuilder();

        ThinkingSlot(int contentIndex) {
            super(contentIndex);
        }

        @Override
        Content initialContent() {
            return new Content.Thinking("");
        }

        @Override
        AssistantMessageEvent createStartEvent() {
            return new AssistantMessageEvent.ThinkingStart(contentIndex, partial());
        }

        AssistantMessageEvent onDelta(String delta) {
            text.append(delta);
            content.set(contentIndex, new Content.Thinking(text.toString()));
            return new AssistantMessageEvent.ThinkingDelta(contentIndex, delta, partial());
        }

        List<AssistantMessageEvent> onSummaryPartDone() {
            if (text.isEmpty() || text.toString().endsWith("\n\n")) {
                return List.of();
            }
            return List.of(onDelta("\n\n"));
        }

        @Override
        AssistantMessageEvent onDone(JsonNode item) {
            if (!"reasoning".equals(item.path("type").asText(""))) {
                return null;
            }
            String confirmed = canonicalThinkingText(item, text.toString());
            text.setLength(0);
            text.append(confirmed);
            String id = item.path("id").asText("");
            if (!id.isBlank()) {
                reasoningIndexById.put(id, contentIndex);
            }
            content.set(contentIndex, new Content.Thinking(
                    confirmed, OpenAiReplayStateCodec.encodeReasoning(confirmed, item)));
            return new AssistantMessageEvent.ThinkingEnd(contentIndex, confirmed, partial());
        }

        @Override
        boolean matches(JsonNode item) {
            return "reasoning".equals(item.path("type").asText(""));
        }
    }

    private final class ToolCallSlot extends Slot {
        final String id;
        final String name;
        final StringBuilder buffer = new StringBuilder();
        JsonNode argumentsNode;

        ToolCallSlot(int contentIndex, String id, String name, JsonNode argumentsNode, String initialArguments) {
            super(contentIndex);
            this.id = id;
            this.name = name;
            this.argumentsNode = argumentsNode;
            if (initialArguments != null && !initialArguments.isEmpty()) {
                buffer.append(initialArguments);
            }
        }

        @Override
        Content initialContent() {
            return new Content.ToolCall(id, name, argumentsNode);
        }

        @Override
        AssistantMessageEvent createStartEvent() {
            return new AssistantMessageEvent.ToolCallStart(contentIndex, partial());
        }

        AssistantMessageEvent onDelta(String delta) {
            buffer.append(delta);
            JsonNode parsed = OpenAiPartialJsonParser.parse(buffer.toString(), argumentsNode);
            if (parsed != null) {
                argumentsNode = parsed;
                content.set(contentIndex, new Content.ToolCall(id, name, argumentsNode));
            }
            return new AssistantMessageEvent.ToolCallDelta(contentIndex, delta, partial());
        }

        List<AssistantMessageEvent> onArgumentsDone(String arguments) {
            String previous = buffer.toString();
            buffer.setLength(0);
            buffer.append(arguments);
            argumentsNode = parseArguments(arguments);
            content.set(contentIndex, new Content.ToolCall(id, name, argumentsNode));
            if (arguments.startsWith(previous)) {
                String suffix = arguments.substring(previous.length());
                if (!suffix.isEmpty()) {
                    return List.of(new AssistantMessageEvent.ToolCallDelta(contentIndex, suffix, partial()));
                }
            }
            return List.of();
        }

        @Override
        AssistantMessageEvent onDone(JsonNode item) {
            if (!"function_call".equals(item.path("type").asText(""))) {
                return null;
            }
            String arguments = item.path("arguments").asText("");
            if (!arguments.isEmpty()) {
                argumentsNode = parseArguments(arguments);
            }
            var toolCall = new Content.ToolCall(id, name, argumentsNode);
            content.set(contentIndex, toolCall);
            return new AssistantMessageEvent.ToolCallEnd(contentIndex, toolCall, partial());
        }

        @Override
        boolean matches(JsonNode item) {
            return "function_call".equals(item.path("type").asText(""));
        }
    }
}
