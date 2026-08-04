package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Slots keyed by {@code output_index} track text/thinking/tool-call blocks;
 * the accumulated content list is kept in sync so partial messages carry
 * current block values. Terminal events ({@code response.completed},
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
        switch (eventName) {
            case "response.output_item.added" -> {
                var item = data.get("item");
                int index = data.path("output_index").asInt(-1);
                if (item == null || !item.isObject() || index < 0) {
                    return List.of();
                }
                return switch (item.path("type").asText("")) {
                    case "message" -> {
                        content.add(new Content.Text(""));
                        var slot = new TextSlot(content.size() - 1);
                        slots.put(index, slot);
                        yield List.of(new AssistantMessageEvent.TextStart(slot.contentIndex, partial()));
                    }
                    case "reasoning" -> {
                        content.add(new Content.Thinking(""));
                        var slot = new ThinkingSlot(content.size() - 1);
                        slots.put(index, slot);
                        yield List.of(new AssistantMessageEvent.ThinkingStart(slot.contentIndex, partial()));
                    }
                    case "function_call" -> {
                        String callId = item.path("call_id").asText("");
                        String itemId = item.path("id").isTextual() ? item.path("id").asText() : null;
                        String name = item.path("name").asText("");
                        String encoded = OpenAiToolCallIds.encode(callId, itemId);
                        JsonNode arguments = parseArguments(item.path("arguments").asText(""));
                        content.add(new Content.ToolCall(encoded, name, arguments));
                        var slot = new ToolCallSlot(content.size() - 1, encoded, name, arguments);
                        slots.put(index, slot);
                        toolCallSeen = true;
                        yield List.of(new AssistantMessageEvent.ToolCallStart(slot.contentIndex, partial()));
                    }
                    default -> List.of();
                };
            }
            case "response.output_text.delta", "response.refusal.delta" -> {
                TextSlot slot = textSlot(data);
                if (slot == null) {
                    return List.of();
                }
                String delta = data.path("delta").asText("");
                slot.text.append(delta);
                content.set(slot.contentIndex, new Content.Text(slot.text.toString()));
                return List.of(new AssistantMessageEvent.TextDelta(slot.contentIndex, delta, partial()));
            }
            case "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
                ThinkingSlot slot = thinkingSlot(data);
                if (slot == null) {
                    return List.of();
                }
                String delta = data.path("delta").asText("");
                slot.text.append(delta);
                content.set(slot.contentIndex, new Content.Thinking(slot.text.toString()));
                return List.of(new AssistantMessageEvent.ThinkingDelta(slot.contentIndex, delta, partial()));
            }
            case "response.function_call_arguments.delta" -> {
                ToolCallSlot slot = toolCallSlot(data);
                if (slot == null) {
                    return List.of();
                }
                String delta = data.path("delta").asText("");
                slot.buffer.append(delta);
                JsonNode parsed = tryParse(slot.buffer.toString());
                if (parsed != null) {
                    slot.argumentsNode = parsed;
                    content.set(slot.contentIndex, new Content.ToolCall(slot.id, slot.name, slot.argumentsNode));
                }
                return List.of(new AssistantMessageEvent.ToolCallDelta(slot.contentIndex, delta, partial()));
            }
            case "response.function_call_arguments.done" -> {
                ToolCallSlot slot = toolCallSlot(data);
                if (slot == null) {
                    return List.of();
                }
                String arguments = data.path("arguments").asText("");
                String previous = slot.buffer.toString();
                slot.buffer.setLength(0);
                slot.buffer.append(arguments);
                slot.argumentsNode = parseArguments(arguments);
                content.set(slot.contentIndex, new Content.ToolCall(slot.id, slot.name, slot.argumentsNode));
                // Update final arguments and backfill any suffix the deltas did not
                // deliver; ToolCallEnd is still emitted only by output_item.done.
                if (arguments.startsWith(previous)) {
                    String suffix = arguments.substring(previous.length());
                    if (!suffix.isEmpty()) {
                        return List.of(new AssistantMessageEvent.ToolCallDelta(
                                slot.contentIndex, suffix, partial()));
                    }
                }
                return List.of();
            }
            case "response.output_item.done" -> {
                var item = data.get("item");
                int index = data.path("output_index").asInt(-1);
                if (item == null || !item.isObject()) {
                    return List.of();
                }
                Slot slot = slots.remove(index);
                if (slot == null) {
                    return List.of();
                }
                String type = item.path("type").asText("");
                if (slot instanceof TextSlot textSlot && type.equals("message")) {
                    String text = contentText(item.get("content"));
                    if (text == null) {
                        text = textSlot.text.toString();
                    }
                    textSlot.text.setLength(0);
                    textSlot.text.append(text);
                    content.set(textSlot.contentIndex, new Content.Text(text));
                    return List.of(new AssistantMessageEvent.TextEnd(textSlot.contentIndex, text, partial()));
                }
                if (slot instanceof ThinkingSlot thinkingSlot && type.equals("reasoning")) {
                    String text = contentText(item.get("content"));
                    if (text == null) {
                        text = thinkingSlot.text.toString();
                    }
                    thinkingSlot.text.setLength(0);
                    thinkingSlot.text.append(text);
                    content.set(thinkingSlot.contentIndex, new Content.Thinking(text));
                    return List.of(new AssistantMessageEvent.ThinkingEnd(thinkingSlot.contentIndex, text, partial()));
                }
                if (slot instanceof ToolCallSlot toolCallSlot && type.equals("function_call")) {
                    String arguments = item.path("arguments").asText("");
                    if (!arguments.isEmpty()) {
                        toolCallSlot.argumentsNode = parseArguments(arguments);
                    }
                    var toolCall = new Content.ToolCall(toolCallSlot.id, toolCallSlot.name, toolCallSlot.argumentsNode);
                    content.set(toolCallSlot.contentIndex, toolCall);
                    return List.of(new AssistantMessageEvent.ToolCallEnd(toolCallSlot.contentIndex, toolCall, partial()));
                }
                return List.of();
            }
            case "response.completed", "response.incomplete" -> {
                sawTerminal = true;
                usage = mapUsage(data.get("response"));
                StopReason reason = eventName.equals("response.incomplete")
                        ? StopReason.LENGTH
                        : (toolCallSeen ? StopReason.TOOL_CALL : StopReason.STOP);
                return List.of(new AssistantMessageEvent.Done(reason, finalMessage(reason)));
            }
            case "response.failed" -> {
                sawTerminal = true;
                usage = mapUsage(data.get("response"));
                var error = data.path("response").path("error");
                String message = error.isMissingNode() || !error.isObject()
                        ? "provider response failed"
                        : error.path("code").asText("unknown") + ": " + error.path("message").asText("no message");
                return List.of(new AssistantMessageEvent.Error(StopReason.ERROR, errorMessage(message)));
            }
            case "error" -> {
                sawTerminal = true;
                String message = data.path("code").asText("unknown")
                        + ": " + data.path("message").asText("no message");
                return List.of(new AssistantMessageEvent.Error(StopReason.ERROR, errorMessage(message)));
            }
            default -> {
                return List.of();
            }
        }
    }

    /** True once a terminal provider event has been seen. */
    boolean terminalHandled() {
        return sawTerminal;
    }

    /** Immutable copy of the accumulated content. */
    List<Content> content() {
        return List.copyOf(content);
    }

    private TextSlot textSlot(JsonNode data) {
        int index = data.path("output_index").asInt(-1);
        if (index < 0) {
            return null;
        }
        return slots.get(index) instanceof TextSlot slot ? slot : null;
    }

    private ThinkingSlot thinkingSlot(JsonNode data) {
        int index = data.path("output_index").asInt(-1);
        if (index < 0) {
            return null;
        }
        return slots.get(index) instanceof ThinkingSlot slot ? slot : null;
    }

    private ToolCallSlot toolCallSlot(JsonNode data) {
        int index = data.path("output_index").asInt(-1);
        if (index < 0) {
            return null;
        }
        return slots.get(index) instanceof ToolCallSlot slot ? slot : null;
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
        // No recognizable content block: let the caller fall back to accumulated deltas.
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
    }

    private static final class TextSlot extends Slot {
        final StringBuilder text = new StringBuilder();

        TextSlot(int contentIndex) {
            super(contentIndex);
        }
    }

    private static final class ThinkingSlot extends Slot {
        final StringBuilder text = new StringBuilder();

        ThinkingSlot(int contentIndex) {
            super(contentIndex);
        }
    }

    private static final class ToolCallSlot extends Slot {
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
    }
}
