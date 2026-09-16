package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.tool.ToolSpec;

import java.util.Objects;

/**
 * Maps a provider-neutral {@link ModelRequest} to the OpenAI Responses API
 * request payload. Replays the full transcript (system prompt, user text,
 * assistant text/tool calls, tool results) with {@code store: false}, maps
 * {@link ToolSpec}s to function tools, and translates the absolute
 * {@link ThinkingLevel} through the provider-local capability map. Mapping
 * failures are reported as {@link IllegalArgumentException} so the adapter
 * can convert them into terminal stream errors.
 */
final class OpenAiRequestMapper {
    private final ObjectMapper objectMapper;

    OpenAiRequestMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    ObjectNode map(ModelRequest request, OpenAiModelCapabilities capabilities) {
        Objects.requireNonNull(request, "request must not be null");
        ObjectNode root = objectMapper.createObjectNode();

        appendMetadata(root, request.model().modelId());
        appendTranscript(root, request.systemPrompt(), request.messages());
        appendTools(root, request.tools());
        applyThinking(root, request.thinkingLevel(), capabilities);

        return root;
    }

    private void appendMetadata(ObjectNode root, String modelId) {
        root.put("model", modelId);
        root.put("stream", true);
        root.put("store", false);
    }

    private void appendTranscript(ObjectNode root, String systemPrompt, java.util.List<Message> messages) {
        ArrayNode input = root.putArray("input");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            var item = input.addObject();
            item.put("role", "system");
            item.putArray("content").addObject().put("type", "input_text").put("text", systemPrompt);
        }
        var context = new ReplayContext();
        for (Message message : messages) {
            appendMessage(input, message, context);
        }
    }

    private void appendTools(ObjectNode root, java.util.List<ToolSpec> tools) {
        if (!tools.isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (ToolSpec spec : tools) {
                toolsArray.add(mapTool(spec));
            }
        }
    }

    private void appendMessage(ArrayNode input, Message message, ReplayContext context) {
        switch (message) {
            case Message.User user -> appendUserMessage(input, user);
            case Message.Assistant assistant -> appendAssistantMessage(input, assistant, context);
            case Message.ToolResultMessage toolResult -> appendToolResultMessage(input, toolResult);
        }
    }

    private void appendUserMessage(ArrayNode input, Message.User user) {
        String text = joinText(user.content());
        if (text.isEmpty()) {
            return;
        }
        var item = input.addObject();
        item.put("role", "user");
        item.putArray("content").addObject().put("type", "input_text").put("text", text);
    }

    private void appendAssistantMessage(ArrayNode input, Message.Assistant assistant, ReplayContext context) {
        for (Content block : assistant.content()) {
            switch (block) {
                case Content.Text textBlock ->
                        appendAssistantText(input, textBlock.text(), context.nextAssistantItemId());
                case Content.ToolCall toolCall ->
                        appendFunctionCall(input, toolCall);
                case Content.Thinking ignored -> {
                    // Thinking blocks are not replayable in v1 and are skipped.
                }
            }
        }
    }

    private void appendAssistantText(ArrayNode input, String text, String itemId) {
        var item = input.addObject();
        item.put("type", "message");
        item.put("role", "assistant");
        item.put("status", "completed");
        item.put("id", itemId);
        item.putArray("content").addObject().put("type", "output_text").put("text", text);
    }

    private void appendFunctionCall(ArrayNode input, Content.ToolCall toolCall) {
        var item = input.addObject();
        item.put("type", "function_call");
        item.put("call_id", OpenAiToolCallIds.callId(toolCall.id()));
        item.put("name", toolCall.name());
        item.put("arguments", toolCall.arguments().toString());
        String itemId = OpenAiToolCallIds.validItemId(OpenAiToolCallIds.itemId(toolCall.id()));
        if (itemId != null) {
            item.put("id", itemId);
        }
    }

    private void appendToolResultMessage(ArrayNode input, Message.ToolResultMessage toolResult) {
        var item = input.addObject();
        item.put("type", "function_call_output");
        item.put("call_id", OpenAiToolCallIds.callId(toolResult.toolCallId()));
        item.put("output", joinText(toolResult.content()));
    }

    private static String joinText(java.util.List<Content> blocks) {
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

    private ObjectNode mapTool(ToolSpec spec) {
        var tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        tool.put("name", spec.name());
        tool.put("description", spec.description());
        JsonNode parameters = spec.parameters();
        if (parameters == null || parameters.isNull()) {
            tool.set("parameters", objectMapper.createObjectNode());
        } else if (parameters.isObject()) {
            tool.set("parameters", parameters);
        } else {
            throw new IllegalArgumentException(
                    "tool " + spec.name() + " parameters must be an object schema but was "
                            + parameters.getNodeType());
        }
        return tool;
    }

    private void applyThinking(ObjectNode root, ThinkingLevel level, OpenAiModelCapabilities capabilities) {
        String effort = resolveReasoningEffort(level, capabilities);
        if (effort != null) {
            root.putObject("reasoning").put("effort", effort);
        }
    }

    private String resolveReasoningEffort(ThinkingLevel level, OpenAiModelCapabilities capabilities) {
        return switch (level) {
            case PROVIDER_DEFAULT -> null;
            case OFF -> {
                if (capabilities != null && capabilities.reasoning()) {
                    String effort = capabilities.reasoningEfforts().get(ThinkingLevel.OFF);
                    if (effort == null) {
                        throw new IllegalArgumentException(
                                "thinking level OFF is not explicitly supported for this reasoning model");
                    }
                    yield effort;
                }
                if (capabilities == null) {
                    throw new IllegalArgumentException(
                            "thinking level OFF cannot be satisfied: model capabilities are unknown");
                }
                // Capabilities explicitly say the model does not reason: no reasoning params needed.
                yield null;
            }
            default -> {
                if (capabilities == null || !capabilities.reasoning()) {
                    throw new IllegalArgumentException(
                            "thinking level " + level + " is not supported by this model");
                }
                String effort = capabilities.reasoningEfforts().get(level);
                if (effort == null) {
                    throw new IllegalArgumentException(
                            "thinking level " + level + " has no reasoning effort mapping for this model");
                }
                yield effort;
            }
        };
    }

    /**
     * Local replay context for generating sequential, OpenAI-safe item ids
     * across replayed assistant output messages.
     */
    private static final class ReplayContext {
        private int assistantItemIndex = 0;

        String nextAssistantItemId() {
            return "msg_jcode_" + (assistantItemIndex++);
        }
    }
}
