package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.ModelReplayState;
import site.pplee.jcode.ai.message.ResponseMetadata;
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
import java.util.Optional;
import java.util.Set;

/**
 * Maps OpenAI Responses API stream events to {@link AssistantMessageEvent}s.
 * Uses a lifecycle-phase dispatcher and polymorphic {@link Slot}s keyed by
 * {@code output_index} to track text, thinking, function-call, and custom
 * grammar-tool blocks. The accumulated content list is kept in sync so
 * partial messages carry current block values. Terminal events
 * ({@code response.completed}, {@code response.incomplete},
 * {@code response.failed}, {@code error}) return the final {@code Done}/{@code Error}
 * event exactly once. Terminal success and {@code response.failed}/{@code incomplete}
 * keep bounded correlation metadata; ordinary SSE {@code error} events do
 * not invent a response id. {@code response.created} records a bounded
 * correlation id without writing partial metadata; a terminal
 * {@code response.id} wins, otherwise the created id is used.
 * {@link #correlationSnapshot()} exposes that bounded state to the adapter
 * for synthetic terminals and is never written to partial metadata. Partial
 * events keep empty metadata.
 * {@code response.incomplete} maps {@code max_output_tokens} to {@code Done(LENGTH)}
 * and every other or missing incomplete reason to {@code Error(ERROR)}.
 * Custom/grammar tools use the request's per-tool input property (never a
 * hardcoded {@code input} field) and emit standard
 * {@code ToolCallStart}/{@code ToolCallDelta}/{@code ToolCallEnd} events.
 */
final class OpenAiEventMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode EMPTY_OBJECT = MAPPER.createObjectNode();

    private final ModelRef sourceModel;
    private final Map<String, String> grammarToolInputProperties;
    private final Optional<OpenAiPricing> pricing;
    private final Optional<OpenAiServiceTier> requestedServiceTier;
    private final List<Content> content = new ArrayList<>();
    private final Map<Integer, Slot> slots = new HashMap<>();
    private final Set<Integer> completedIndexes = new HashSet<>();
    private final Map<String, Integer> reasoningIndexById = new HashMap<>();
    private Optional<String> providerRequestId = Optional.empty();
    private String createdResponseId;
    private ResponseMetadata metadata = ResponseMetadata.empty();
    private Usage usage = Usage.zero();
    private boolean sawTerminal;
    private boolean toolCallSeen;

    OpenAiEventMapper(ModelRef sourceModel) {
        this(sourceModel, Map.of());
    }

    OpenAiEventMapper(ModelRef sourceModel, Map<String, String> grammarToolInputProperties) {
        this(sourceModel, grammarToolInputProperties, Optional.empty(), Optional.empty());
    }

    OpenAiEventMapper(
            ModelRef sourceModel,
            Map<String, String> grammarToolInputProperties,
            Optional<OpenAiPricing> pricing,
            Optional<OpenAiServiceTier> requestedServiceTier
    ) {
        this.sourceModel = Objects.requireNonNull(sourceModel, "sourceModel must not be null");
        this.grammarToolInputProperties = Map.copyOf(Objects.requireNonNull(
                grammarToolInputProperties, "grammarToolInputProperties must not be null"));
        this.pricing = Objects.requireNonNull(pricing, "pricing must not be null");
        this.requestedServiceTier = Objects.requireNonNull(
                requestedServiceTier, "requestedServiceTier must not be null");
    }

    /**
     * Records the allowlisted {@code x-request-id} from the HTTP response.
     * Other headers must not be passed here.
     */
    void acceptProviderRequestId(Optional<String> providerRequestId) {
        this.providerRequestId = providerRequestId == null ? Optional.empty() : providerRequestId;
    }

    /**
     * Bounded correlation known so far. Safe to copy onto adapter-owned
     * synthetic terminals. Never written to {@link #partial()}. Includes a
     * created or terminal response id, the allowlisted request id, and a raw
     * reason only after terminal metadata was constructed.
     */
    ResponseMetadata correlationSnapshot() {
        if (!metadata.isEmpty()) {
            return metadata;
        }
        return OpenAiResponseCorrelation.knownSoFar(createdResponseId, providerRequestId);
    }

    /** Process one provider event; returns the Jcode events to push (possibly empty). */
    List<AssistantMessageEvent> onEvent(String eventName, JsonNode data) {
        return switch (eventName) {
            case "response.output_item.added" -> handleItemAdded(data);
            case "response.output_text.delta",
                 "response.refusal.delta",
                 "response.reasoning_text.delta",
                 "response.reasoning_summary_text.delta",
                 "response.function_call_arguments.delta",
                 "response.custom_tool_call_input.delta" -> handleDelta(eventName, data);
            case "response.reasoning_summary_part.done" -> handleSummaryPartDone(data);
            case "response.function_call_arguments.done" -> handleArgumentsDone(data);
            case "response.custom_tool_call_input.done" -> handleCustomInputDone(data);
            case "response.output_item.done" -> handleItemDone(data);
            case "response.created" -> handleCreated(data);
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
        if (slot instanceof ToolCallSlot || slot instanceof CustomToolCallSlot) {
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
            case "custom_tool_call" -> customToolCallSlot(item, contentIndex);
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
        return switch (eventName) {
            case "response.output_text.delta", "response.refusal.delta" ->
                    slot instanceof TextSlot textSlot
                            ? List.of(textSlot.onDelta(data.path("delta").asText("")))
                            : List.of();
            case "response.reasoning_text.delta", "response.reasoning_summary_text.delta" ->
                    slot instanceof ThinkingSlot thinkingSlot
                            ? List.of(thinkingSlot.onDelta(data.path("delta").asText("")))
                            : List.of();
            case "response.function_call_arguments.delta" -> {
                if (slot instanceof CustomToolCallSlot) {
                    throw new IllegalStateException("function-call argument delta targeted a custom tool slot");
                }
                yield slot instanceof ToolCallSlot toolSlot
                        ? List.of(toolSlot.onDelta(data.path("delta").asText("")))
                        : List.of();
            }
            case "response.custom_tool_call_input.delta" -> {
                if (slot instanceof ToolCallSlot) {
                    throw new IllegalStateException("custom tool input delta targeted a function-call slot");
                }
                if (!(slot instanceof CustomToolCallSlot customSlot)) {
                    yield List.of();
                }
                yield customSlot.onInputDelta(requiredTextualCustomDelta(data));
            }
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
        Slot slot = slots.get(index);
        if (slot instanceof CustomToolCallSlot) {
            throw new IllegalStateException("function-call argument done targeted a custom tool slot");
        }
        if (slot instanceof ToolCallSlot toolSlot) {
            String arguments = data.path("arguments").asText("");
            return toolSlot.onArgumentsDone(arguments);
        }
        return List.of();
    }

    private List<AssistantMessageEvent> handleCustomInputDone(JsonNode data) {
        int index = data.path("output_index").asInt(-1);
        if (index < 0) {
            return List.of();
        }
        Slot slot = slots.get(index);
        if (slot instanceof ToolCallSlot) {
            throw new IllegalStateException("custom tool input done targeted a function-call slot");
        }
        if (!(slot instanceof CustomToolCallSlot customSlot)) {
            return List.of();
        }
        if (!data.has("input")) {
            return customSlot.onInputDone(customSlot.currentInput());
        }
        return customSlot.onInputDone(requiredTextualCustomInput(data, "custom tool input done"));
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
            var events = new ArrayList<AssistantMessageEvent>();
            events.add(slot.createStartEvent());
            events.addAll(finalizeSlot(index, slot, item));
            return events;
        }
        if (!slot.matches(item)) {
            throw new IllegalStateException("output item type conflict at index " + index);
        }
        return finalizeSlot(index, slot, item);
    }

    private List<AssistantMessageEvent> finalizeSlot(int index, Slot slot, JsonNode item) {
        slots.remove(index);
        completedIndexes.add(index);
        return slot.onDone(item);
    }

    private List<AssistantMessageEvent> handleTerminal(String eventName, JsonNode data) {
        sawTerminal = true;
        return switch (eventName) {
            case "response.completed" -> {
                acceptTerminalResponse(data.get("response"), true);
                var events = new ArrayList<>(recoverCustomToolsFromTerminal(data.get("response")));
                StopReason reason = toolCallSeen ? StopReason.TOOL_CALL : StopReason.STOP;
                events.add(new AssistantMessageEvent.Done(reason, finalMessage(reason)));
                yield events;
            }
            case "response.incomplete" -> {
                acceptTerminalResponse(data.get("response"), true);
                var events = new ArrayList<>(recoverCustomToolsFromTerminal(data.get("response")));
                events.add(mapIncomplete(data.get("response")));
                yield events;
            }
            case "response.failed" -> {
                acceptTerminalResponse(data.get("response"), true);
                var error = data.path("response").path("error");
                String message = error.isMissingNode() || !error.isObject()
                        ? "provider response failed"
                        : error.path("code").asText("unknown") + ": " + error.path("message").asText("no message");
                yield List.of(new AssistantMessageEvent.Error(StopReason.ERROR, errorMessage(message)));
            }
            case "error" -> {
                metadata = OpenAiResponseCorrelation.knownSoFar(createdResponseId, providerRequestId);
                String message = data.path("code").asText("unknown")
                        + ": " + data.path("message").asText("no message");
                yield List.of(new AssistantMessageEvent.Error(StopReason.ERROR, errorMessage(message)));
            }
            default -> List.of();
        };
    }

    /**
     * Records {@code response.created.id} for later terminal fallback.
     * Emits no events and does not write partial metadata.
     */
    private List<AssistantMessageEvent> handleCreated(JsonNode data) {
        JsonNode response = data == null ? null : data.get("response");
        if (response == null || response.isNull()) {
            return List.of();
        }
        if (!response.isObject()) {
            throw new IllegalStateException("protocol mapping error: response.created response is not an object");
        }
        String recorded = OpenAiResponseCorrelation.createdResponseId(response.get("id"));
        if (recorded != null) {
            createdResponseId = recorded;
        }
        return List.of();
    }

    private void acceptTerminalResponse(JsonNode response, boolean includeRawReason) {
        metadata = OpenAiResponseCorrelation.fromResponse(
                response, providerRequestId, includeRawReason, createdResponseId);
        usage = mapUsage(response);
        backfillEncryptedReasoning(response);
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

    private Usage mapUsage(JsonNode response) {
        if (response == null || !response.has("usage") || response.get("usage").isNull()) {
            return Usage.zero();
        }
        JsonNode usageNode = response.get("usage");
        if (!usageNode.isObject()) {
            throw new IllegalStateException("protocol mapping error: usage is not an object");
        }
        JsonNode inputDetails = usageDetails(usageNode, "input_tokens_details");
        JsonNode outputDetails = usageDetails(usageNode, "output_tokens_details");
        long inputTokens = tokenField(usageNode, "input_tokens");
        long cached = tokenField(inputDetails, "cached_tokens");
        long cacheWrite = tokenField(inputDetails, "cache_write_tokens");
        long cacheTokens = addExact("cache tokens", cached, cacheWrite);
        if (cacheTokens > inputTokens) {
            throw new IllegalStateException(
                    "protocol mapping error: cache tokens exceed input_tokens");
        }
        long input = subtractExact("unbundled input tokens", inputTokens, cacheTokens);
        long output = tokenField(usageNode, "output_tokens");
        long reasoning = tokenField(outputDetails, "reasoning_tokens");
        if (reasoning > output) {
            throw new IllegalStateException(
                    "protocol mapping error: reasoning_tokens exceed output_tokens");
        }
        long total = tokenField(usageNode, "total_tokens");
        if (total <= 0) {
            total = addExact("total tokens",
                    addExact("total tokens", input, output),
                    cacheTokens);
        }
        var tokens = new Usage(input, output, cached, cacheWrite, total, reasoning, Optional.empty());
        return attachCost(tokens, response);
    }

    private Usage attachCost(Usage tokens, JsonNode response) {
        if (pricing.isEmpty()) {
            return tokens;
        }
        return pricing.get().estimate(sourceModel.modelId(), tokens, resolveServiceTier(response))
                .map(tokens::withCost)
                .orElse(tokens);
    }

    /**
     * Applies a terminal {@code service_tier} only when it is a known wire
     * value. Absent, null, or blank falls back to the explicit request tier.
     * An unknown non-empty tier is forward-compatible and does not inherit
     * the requested-tier multiplier. A present non-string value is a
     * protocol mapping error.
     */
    private Optional<OpenAiServiceTier> resolveServiceTier(JsonNode response) {
        if (response == null || !response.isObject()
                || !response.has("service_tier") || response.get("service_tier").isNull()) {
            return requestedServiceTier;
        }
        JsonNode tier = response.get("service_tier");
        if (!tier.isTextual()) {
            throw new IllegalStateException("protocol mapping error: service_tier is not a string");
        }
        String wire = tier.asText();
        if (wire.isBlank()) {
            return requestedServiceTier;
        }
        return OpenAiServiceTier.fromWireValue(wire);
    }

    private static JsonNode usageDetails(JsonNode usage, String field) {
        if (usage == null || !usage.isObject() || !usage.has(field) || usage.get(field).isNull()) {
            return null;
        }
        JsonNode details = usage.get(field);
        if (!details.isObject()) {
            throw new IllegalStateException("protocol mapping error: usage." + field + " is not an object");
        }
        return details;
    }

    private static long tokenField(JsonNode node, String field) {
        if (node == null || !node.isObject() || !node.has(field) || node.get(field).isNull()) {
            return 0L;
        }
        return exactNonNegativeLong(node.get(field), field);
    }

    /**
     * Converts a JSON number to a non-negative {@code long} without
     * saturation or truncation. Fractional, negative, and out-of-range
     * values are protocol mapping errors.
     */
    private static long exactNonNegativeLong(JsonNode value, String field) {
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException("protocol mapping error: usage." + field + " is not a number");
        }
        if (!value.canConvertToExactIntegral()) {
            throw new IllegalStateException("protocol mapping error: usage." + field + " is not an integer");
        }
        if (!value.canConvertToLong()) {
            throw new IllegalStateException("protocol mapping error: usage." + field + " exceeds long range");
        }
        long tokens = value.longValue();
        if (tokens < 0) {
            throw new IllegalStateException("protocol mapping error: usage." + field + " is negative");
        }
        return tokens;
    }

    private static long addExact(String what, long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new IllegalStateException("protocol mapping error: " + what + " overflow", e);
        }
    }

    private static long subtractExact(String what, long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException e) {
            throw new IllegalStateException("protocol mapping error: " + what + " overflow", e);
        }
    }

    private Message.Assistant partial() {
        return new Message.Assistant(
                List.copyOf(content), StopReason.STOP, null, Usage.zero(), Instant.now(), sourceModel);
    }

    private Message.Assistant finalMessage(StopReason reason) {
        return new Message.Assistant(
                List.copyOf(content), reason, null, usage, Instant.now(), sourceModel, metadata);
    }

    private Message.Assistant errorMessage(String message) {
        return new Message.Assistant(
                List.copyOf(content), StopReason.ERROR, message, usage, Instant.now(), sourceModel, metadata);
    }

    private abstract static class Slot {
        final int contentIndex;

        Slot(int contentIndex) {
            this.contentIndex = contentIndex;
        }

        abstract Content initialContent();

        abstract AssistantMessageEvent createStartEvent();

        abstract List<AssistantMessageEvent> onDone(JsonNode item);

        abstract boolean matches(JsonNode item);
    }

    private CustomToolCallSlot customToolCallSlot(JsonNode item, int contentIndex) {
        String name = item.path("name").asText("");
        if (name.isBlank()) {
            throw new IllegalStateException("custom tool call is missing a name");
        }
        String inputProperty = grammarToolInputProperties.get(name);
        if (inputProperty == null) {
            throw new IllegalStateException("custom tool \"" + name + "\" has no grammar input property");
        }
        String callId = item.path("call_id").asText("");
        String itemId = item.path("id").isTextual() ? item.path("id").asText() : null;
        String encoded = OpenAiToolCallIds.encode(callId, itemId);
        String input = optionalTextualCustomInput(item, "");
        return new CustomToolCallSlot(contentIndex, encoded, callId, name, inputProperty, input);
    }

    /**
     * Terminal {@code response.output} is authoritative for custom tools.
     * Open slots (added/delta without {@code output_item.done}) are finalized
     * in place; already completed items are not replayed; items never added
     * recover as Start → Delta → End.
     */
    private List<AssistantMessageEvent> recoverCustomToolsFromTerminal(JsonNode response) {
        if (response == null || !response.isObject()) {
            return List.of();
        }
        JsonNode output = response.get("output");
        if (output == null || !output.isArray()) {
            return List.of();
        }
        var events = new ArrayList<AssistantMessageEvent>();
        for (int i = 0; i < output.size(); i++) {
            JsonNode item = output.get(i);
            if (!"custom_tool_call".equals(item.path("type").asText(""))) {
                continue;
            }
            OpenCustomSlot open = findOpenCustomSlot(item);
            if (open != null) {
                events.addAll(finalizeSlot(open.index(), open.slot(), item));
                continue;
            }
            if (completedCustomToolCall(item)) {
                continue;
            }
            int index = unusedOutputIndex(i);
            Slot slot = openSlot(index, item);
            if (slot == null) {
                continue;
            }
            events.add(slot.createStartEvent());
            events.addAll(finalizeSlot(index, slot, item));
        }
        return events;
    }

    private OpenCustomSlot findOpenCustomSlot(JsonNode item) {
        for (var entry : slots.entrySet()) {
            if (entry.getValue() instanceof CustomToolCallSlot custom && custom.matchesIdentity(item)) {
                return new OpenCustomSlot(entry.getKey(), custom);
            }
        }
        return null;
    }

    private boolean completedCustomToolCall(JsonNode item) {
        String callId = item.path("call_id").asText("");
        String encoded = encodedCustomToolCallId(item);
        for (Content block : content) {
            if (!(block instanceof Content.ToolCall toolCall)) {
                continue;
            }
            if (encoded != null && encoded.equals(toolCall.id())) {
                return true;
            }
            if (!callId.isBlank() && sameCallId(toolCall.id(), callId)) {
                return true;
            }
        }
        return false;
    }

    private static String encodedCustomToolCallId(JsonNode item) {
        try {
            String callId = item.path("call_id").asText("");
            String itemId = item.path("id").isTextual() ? item.path("id").asText() : null;
            return OpenAiToolCallIds.encode(callId, itemId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean sameCallId(String encoded, String callId) {
        return OpenAiToolCallIds.decode(encoded)
                .map(decoded -> callId.equals(decoded.callId()))
                .orElse(false);
    }

    private static String requiredTextualCustomDelta(JsonNode data) {
        JsonNode delta = data.get("delta");
        if (delta == null || !delta.isTextual()) {
            throw new IllegalStateException("custom tool input delta must be a string");
        }
        return delta.asText();
    }

    private static String requiredTextualCustomInput(JsonNode node, String context) {
        JsonNode input = node.get("input");
        if (input == null || !input.isTextual()) {
            throw new IllegalStateException(context + " is not a string");
        }
        return input.asText();
    }

    /**
     * Absent {@code input} keeps {@code whenMissing}. A present non-string
     * value is a protocol error, including JSON null.
     */
    private static String optionalTextualCustomInput(JsonNode node, String whenMissing) {
        if (!node.has("input")) {
            return whenMissing;
        }
        return requiredTextualCustomInput(node, "custom tool call input");
    }

    private record OpenCustomSlot(int index, CustomToolCallSlot slot) {
    }

    private int unusedOutputIndex(int preferred) {
        if (!slots.containsKey(preferred) && !completedIndexes.contains(preferred)) {
            return preferred;
        }
        int index = 0;
        while (slots.containsKey(index) || completedIndexes.contains(index)) {
            index++;
        }
        return index;
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
        List<AssistantMessageEvent> onDone(JsonNode item) {
            if (!"message".equals(item.path("type").asText(""))) {
                return List.of();
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
            return List.of(new AssistantMessageEvent.TextEnd(contentIndex, confirmed, partial()));
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
        List<AssistantMessageEvent> onDone(JsonNode item) {
            if (!"reasoning".equals(item.path("type").asText(""))) {
                return List.of();
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
            return List.of(new AssistantMessageEvent.ThinkingEnd(contentIndex, confirmed, partial()));
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
        List<AssistantMessageEvent> onDone(JsonNode item) {
            if (!"function_call".equals(item.path("type").asText(""))) {
                return List.of();
            }
            String arguments = item.path("arguments").asText("");
            if (!arguments.isEmpty()) {
                argumentsNode = parseArguments(arguments);
            }
            var toolCall = new Content.ToolCall(id, name, argumentsNode);
            content.set(contentIndex, toolCall);
            return List.of(new AssistantMessageEvent.ToolCallEnd(contentIndex, toolCall, partial()));
        }

        @Override
        boolean matches(JsonNode item) {
            return "function_call".equals(item.path("type").asText(""));
        }
    }

    /**
     * Custom/grammar tool slot. Accumulated raw input stays in this slot;
     * only the standard {@code {property: input}} object is written to
     * {@link Content.ToolCall}. The JSON scratch used for deltas never
     * enters the transcript.
     */
    private final class CustomToolCallSlot extends Slot {
        final String id;
        final String callId;
        final String name;
        final String inputProperty;
        final OpenAiConstrainedSampling.GrammarToolInputJsonBuffer buffer =
                new OpenAiConstrainedSampling.GrammarToolInputJsonBuffer();
        String rawInput;

        CustomToolCallSlot(
                int contentIndex,
                String id,
                String callId,
                String name,
                String inputProperty,
                String initialInput
        ) {
            super(contentIndex);
            this.id = id;
            this.callId = callId == null ? "" : callId;
            this.name = name;
            this.inputProperty = inputProperty;
            this.rawInput = initialInput == null ? "" : initialInput;
        }

        String currentInput() {
            return rawInput;
        }

        boolean matchesIdentity(JsonNode item) {
            String itemCallId = item.path("call_id").asText("");
            if (!callId.isBlank() && callId.equals(itemCallId)) {
                return true;
            }
            String itemId = item.path("id").isTextual() ? item.path("id").asText() : null;
            try {
                return id.equals(OpenAiToolCallIds.encode(itemCallId, itemId));
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        Content initialContent() {
            return new Content.ToolCall(id, name, argumentsObject());
        }

        @Override
        AssistantMessageEvent createStartEvent() {
            return new AssistantMessageEvent.ToolCallStart(contentIndex, partial());
        }

        List<AssistantMessageEvent> onInputDelta(String delta) {
            return applyInput(rawInput + (delta == null ? "" : delta), false);
        }

        List<AssistantMessageEvent> onInputDone(String input) {
            return applyInput(input, true);
        }

        @Override
        List<AssistantMessageEvent> onDone(JsonNode item) {
            if (!"custom_tool_call".equals(item.path("type").asText(""))) {
                return List.of();
            }
            String next = optionalTextualCustomInput(item, rawInput);
            var events = new ArrayList<AssistantMessageEvent>(applyInput(next, true));
            var toolCall = new Content.ToolCall(id, name, argumentsObject());
            content.set(contentIndex, toolCall);
            events.add(new AssistantMessageEvent.ToolCallEnd(contentIndex, toolCall, partial()));
            return events;
        }

        @Override
        boolean matches(JsonNode item) {
            return "custom_tool_call".equals(item.path("type").asText(""));
        }

        private List<AssistantMessageEvent> applyInput(String nextInput, boolean close) {
            String delta = buffer.append(inputProperty, nextInput, close);
            rawInput = buffer.input();
            content.set(contentIndex, new Content.ToolCall(id, name, argumentsObject()));
            if (delta == null) {
                return List.of();
            }
            return List.of(new AssistantMessageEvent.ToolCallDelta(contentIndex, delta, partial()));
        }

        private JsonNode argumentsObject() {
            return OpenAiConstrainedSampling.argumentsObject(MAPPER, inputProperty, rawInput);
        }
    }
}
