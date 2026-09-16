package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps OpenAI Responses API stream events to {@link AssistantMessageEvent}s.
 * Uses a lifecycle-phase dispatcher and polymorphic {@link Slot}s keyed by
 * {@code output_index} to track text, thinking, and tool-call blocks. The
 * accumulated content list is kept in sync so partial messages carry current
 * block values. Terminal events ({@code response.completed},
 * {@code response.incomplete}, {@code response.failed}, {@code error}) return
 * the final {@code Done}/{@code Error} event exactly once.
 */
final class OpenAiEventMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode EMPTY_OBJECT = MAPPER.createObjectNode();

    private final List<Content> content = new ArrayList<>();
    private final Map<Integer, Slot> slots = new HashMap<>();
    private Usage usage = Usage.zero();
    private boolean sawTerminal;
    private boolean toolCallSeen;

    /** Process one provider event; returns the Jcode events to push (possibly empty). */
    List<AssistantMessageEvent> onEvent(String eventName, JsonNode data) {
        return switch (eventName) {
            case "response.output_item.added" -> handleItemAdded(data);
            case "response.output_text.delta",
                 "response.refusal.delta",
                 "response.reasoning_text.delta",
                 "response.reasoning_summary_text.delta",
                 "response.function_call_arguments.delta" -> handleDelta(eventName, data);
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
        Slot slot = createSlot(item, content.size());
        if (slot == null) {
            return List.of();
        }
        content.add(slot.initialContent());
        slots.put(index, slot);
        if (slot instanceof ToolCallSlot) {
            toolCallSeen = true;
        }
        return List.of(slot.createStartEvent());
    }

    private Slot createSlot(JsonNode item, int contentIndex) {
        String type = item.path("type").asText("");
        return switch (type) {
            case "message" -> new TextSlot(contentIndex);
            case "reasoning" -> new ThinkingSlot(contentIndex);
            case "function_call" -> {
                String callId = item.path("call_id").asText("");
                String itemId = item.path("id").isTextual() ? item.path("id").asText() : null;
                String name = item.path("name").asText("");
                String encoded = OpenAiToolCallIds.encode(callId, itemId);
                JsonNode arguments = parseArguments(item.path("arguments").asText(""));
                yield new ToolCallSlot(contentIndex, encoded, name, arguments);
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
        Slot slot = slots.remove(index);
        if (slot == null) {
            return List.of();
        }
        AssistantMessageEvent endEvent = slot.onDone(item);
        return endEvent != null ? List.of(endEvent) : List.of();
    }

    private List<AssistantMessageEvent> handleTerminal(String eventName, JsonNode data) {
        sawTerminal = true;
        return switch (eventName) {
            case "response.completed", "response.incomplete" -> {
                usage = mapUsage(data.get("response"));
                StopReason reason = eventName.equals("response.incomplete")
                        ? StopReason.LENGTH
                        : (toolCallSeen ? StopReason.TOOL_CALL : StopReason.STOP);
                yield List.of(new AssistantMessageEvent.Done(reason, finalMessage(reason)));
            }
            case "response.failed" -> {
                usage = mapUsage(data.get("response"));
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

    /** True once a terminal provider event has been seen. */
    boolean terminalHandled() {
        return sawTerminal;
    }

    /** Immutable copy of the accumulated content. */
    List<Content> content() {
        return List.copyOf(content);
    }

    private static String contentText(JsonNode array) {
        if (array == null || !array.isArray()) {
            return null;
        }
        var sb = new StringBuilder();
        for (JsonNode block : array) {
            String type = block.path("type").asText("");
            if (type.equals("output_text") || type.equals("refusal") || type.equals("reasoning_text")) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(block.path("text").asText(""));
            }
        }
        return sb.isEmpty() ? null : sb.toString();
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
        return new Message.Assistant(List.copyOf(content), StopReason.STOP, null, Usage.zero(), Instant.now());
    }

    private Message.Assistant finalMessage(StopReason reason) {
        return new Message.Assistant(List.copyOf(content), reason, null, usage, Instant.now());
    }

    private Message.Assistant errorMessage(String message) {
        return new Message.Assistant(List.copyOf(content), StopReason.ERROR, message, usage, Instant.now());
    }

    private abstract static class Slot {
        final int contentIndex;

        Slot(int contentIndex) {
            this.contentIndex = contentIndex;
        }

        abstract Content initialContent();

        abstract AssistantMessageEvent createStartEvent();

        abstract AssistantMessageEvent onDone(JsonNode item);
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
            String confirmed = contentText(item.get("content"));
            if (confirmed == null) {
                confirmed = text.toString();
            }
            text.setLength(0);
            text.append(confirmed);
            content.set(contentIndex, new Content.Text(confirmed));
            return new AssistantMessageEvent.TextEnd(contentIndex, confirmed, partial());
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

        @Override
        AssistantMessageEvent onDone(JsonNode item) {
            if (!"reasoning".equals(item.path("type").asText(""))) {
                return null;
            }
            String confirmed = contentText(item.get("content"));
            if (confirmed == null) {
                confirmed = text.toString();
            }
            text.setLength(0);
            text.append(confirmed);
            content.set(contentIndex, new Content.Thinking(confirmed));
            return new AssistantMessageEvent.ThinkingEnd(contentIndex, confirmed, partial());
        }
    }

    private final class ToolCallSlot extends Slot {
        final String id;
        final String name;
        final StringBuilder buffer = new StringBuilder();
        JsonNode argumentsNode;

        ToolCallSlot(int contentIndex, String id, String name, JsonNode argumentsNode) {
            super(contentIndex);
            this.id = id;
            this.name = name;
            this.argumentsNode = argumentsNode;
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
            JsonNode parsed = tryParse(buffer.toString());
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
    }
}
