package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.tool.ToolSpec;

import java.util.Objects;

/**
 * Maps a provider-neutral {@link ModelRequest} to the OpenAI Responses API
 * request payload. Combines the system prompt, a planned input transcript,
 * function tools, and the reasoning-request policy. Sequence, pairing, and
 * replay rules live in {@link OpenAiTranscriptPlanner}. Mapping failures are
 * reported as {@link IllegalArgumentException} so the adapter can convert
 * them into terminal stream errors.
 */
final class OpenAiRequestMapper {
    private final ObjectMapper objectMapper;
    private final OpenAiTranscriptPlanner planner;

    OpenAiRequestMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.planner = new OpenAiTranscriptPlanner(objectMapper);
    }

    ObjectNode map(ModelRequest request, OpenAiModelCapabilities capabilities) {
        Objects.requireNonNull(request, "request must not be null");
        ObjectNode root = objectMapper.createObjectNode();

        appendMetadata(root, request.model().modelId());
        var plan = planner.plan(request.messages(), request.model());
        appendInput(root, request.systemPrompt(), plan);
        appendTools(root, request.tools());
        applyThinking(root, request.thinkingLevel(), capabilities, plan.replayedReasoning());

        return root;
    }

    private void appendMetadata(ObjectNode root, String modelId) {
        root.put("model", modelId);
        root.put("stream", true);
        root.put("store", false);
    }

    private void appendInput(ObjectNode root, String systemPrompt, OpenAiTranscriptPlanner.OpenAiTranscriptPlan plan) {
        ArrayNode input = root.putArray("input");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            var item = input.addObject();
            item.put("role", "system");
            item.putArray("content").addObject().put("type", "input_text").put("text", systemPrompt);
        }
        for (ObjectNode item : plan.inputItems()) {
            input.add(item);
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

    private void applyThinking(
            ObjectNode root,
            ThinkingLevel level,
            OpenAiModelCapabilities capabilities,
            boolean replayedReasoning
    ) {
        String effort = resolveReasoningEffort(level, capabilities);
        if (effort != null) {
            ObjectNode reasoning = root.putObject("reasoning");
            reasoning.put("effort", effort);
            if (level != ThinkingLevel.OFF) {
                reasoning.put("summary", "auto");
            }
        }
        if (shouldIncludeEncryptedReasoning(level, capabilities, replayedReasoning)) {
            root.putArray("include").add("reasoning.encrypted_content");
        }
    }

    private static boolean shouldIncludeEncryptedReasoning(
            ThinkingLevel level,
            OpenAiModelCapabilities capabilities,
            boolean replayedReasoning
    ) {
        if (level == ThinkingLevel.OFF) {
            return false;
        }
        if (replayedReasoning || level != ThinkingLevel.PROVIDER_DEFAULT) {
            return true;
        }
        return capabilities != null && capabilities.reasoning();
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
}
