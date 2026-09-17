package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.client.CacheRetention;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.client.PromptCacheOptions;
import site.pplee.jcode.ai.client.ToolChoice;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.tool.ToolSpec;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maps a provider-neutral {@link ModelRequest} to the OpenAI Responses API
 * request payload. Combines the system prompt, a planned input transcript,
 * function or custom grammar tools, reasoning-request policy, and explicit
 * request/cache controls. Sequence, pairing, and replay rules live in
 * {@link OpenAiTranscriptPlanner}. Mapping failures are reported as
 * {@link IllegalArgumentException} so the adapter can convert them into
 * terminal stream errors. Unsupported explicit options fail; they are never
 * silently dropped. Only {@code PREFER} constrained sampling may degrade.
 */
final class OpenAiRequestMapper {
    private final ObjectMapper objectMapper;
    private final OpenAiTranscriptPlanner planner;

    OpenAiRequestMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.planner = new OpenAiTranscriptPlanner(objectMapper);
    }

    ObjectNode map(ModelRequest request, OpenAiModelCapabilities capabilities) {
        return map(request, capabilities, OpenAiResponsesCompatibility.openai(), null);
    }

    ObjectNode map(
            ModelRequest request,
            OpenAiModelCapabilities capabilities,
            OpenAiResponsesCompatibility compatibility
    ) {
        return map(request, capabilities, compatibility, null);
    }

    ObjectNode map(
            ModelRequest request,
            OpenAiModelCapabilities capabilities,
            OpenAiResponsesCompatibility compatibility,
            OpenAiServiceTier serviceTier
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(compatibility, "compatibility must not be null");
        OpenAiRequestUnicode.requireWellFormedModel(request.model());
        for (ToolSpec spec : request.tools()) {
            OpenAiRequestUnicode.requireWellFormedIdentity(spec.name(), "tool name");
        }
        ObjectNode root = objectMapper.createObjectNode();

        appendMetadata(root, request.model().modelId());
        boolean imageInput = capabilities != null && capabilities.imageInput();
        Map<String, String> grammarInputProperties = OpenAiConstrainedSampling.grammarInputProperties(
                request.tools(), compatibility.grammarTools());
        var plan = planner.plan(request.messages(), request.model(), imageInput, grammarInputProperties);
        appendInput(root, request.systemPrompt(), plan, promptRole(capabilities, compatibility));
        appendTools(root, request.tools(), compatibility);
        applyThinking(root, request.thinkingLevel(), capabilities, plan.replayedReasoning());
        applyRequestControls(root, request, capabilities, compatibility, serviceTier);

        return root;
    }

    /**
     * Send {@code developer} only when the model prefers it and the endpoint
     * accepts it. Unknown or legacy capabilities keep {@code system}.
     */
    static String promptRole(
            OpenAiModelCapabilities capabilities,
            OpenAiResponsesCompatibility compatibility
    ) {
        Objects.requireNonNull(compatibility, "compatibility must not be null");
        if (capabilities != null
                && capabilities.developerRolePreferred()
                && compatibility.developerRole()) {
            return "developer";
        }
        return "system";
    }

    private void appendMetadata(ObjectNode root, String modelId) {
        root.put("model", modelId);
        root.put("stream", true);
        root.put("store", false);
    }

    private void appendInput(
            ObjectNode root,
            String systemPrompt,
            OpenAiTranscriptPlanner.OpenAiTranscriptPlan plan,
            String promptRole
    ) {
        ArrayNode input = root.putArray("input");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            var item = input.addObject();
            item.put("role", promptRole);
            item.putArray("content").addObject()
                    .put("type", "input_text")
                    .put("text", OpenAiRequestUnicode.sanitizeText(systemPrompt));
        }
        for (ObjectNode item : plan.inputItems()) {
            input.add(item);
        }
    }

    private void appendTools(ObjectNode root, List<ToolSpec> tools, OpenAiResponsesCompatibility compatibility) {
        if (!tools.isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (ToolSpec spec : tools) {
                toolsArray.add(OpenAiConstrainedSampling.mapTool(objectMapper, spec, compatibility));
            }
        }
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

    private void applyRequestControls(
            ObjectNode root,
            ModelRequest request,
            OpenAiModelCapabilities capabilities,
            OpenAiResponsesCompatibility compatibility,
            OpenAiServiceTier serviceTier
    ) {
        ModelRequestOptions options = request.options();
        applyMaxOutputTokens(root, options.maxOutputTokens(), compatibility);
        applyTemperature(root, options.temperature(), capabilities);
        applyToolChoice(root, options.toolChoice(), request.tools(), capabilities, compatibility);
        applyPromptCache(root, options.promptCache(), compatibility);
        if (serviceTier != null) {
            root.put("service_tier", serviceTier.wireValue());
        }
    }

    private static void applyMaxOutputTokens(
            ObjectNode root,
            Integer maxOutputTokens,
            OpenAiResponsesCompatibility compatibility
    ) {
        if (maxOutputTokens == null) {
            return;
        }
        if (!compatibility.maxOutputTokens()) {
            throw new IllegalArgumentException("maxOutputTokens is not supported by this endpoint");
        }
        root.put("max_output_tokens", Math.max(maxOutputTokens, 16));
    }

    private static void applyTemperature(
            ObjectNode root,
            Double temperature,
            OpenAiModelCapabilities capabilities
    ) {
        if (temperature == null) {
            return;
        }
        if (capabilities == null || !capabilities.temperature()) {
            throw new IllegalArgumentException("temperature is not supported by this model");
        }
        root.put("temperature", temperature);
    }

    private void applyToolChoice(
            ObjectNode root,
            ToolChoice toolChoice,
            List<ToolSpec> tools,
            OpenAiModelCapabilities capabilities,
            OpenAiResponsesCompatibility compatibility
    ) {
        if (toolChoice == null || toolChoice == ToolChoice.Mode.AUTO) {
            return;
        }
        if (capabilities == null || !capabilities.toolChoice()) {
            throw new IllegalArgumentException("tool choice is not supported by this model");
        }
        switch (toolChoice) {
            case ToolChoice.Mode.NONE -> root.put("tool_choice", "none");
            case ToolChoice.Mode.REQUIRED -> {
                if (tools.isEmpty()) {
                    throw new IllegalArgumentException("tool choice REQUIRED requires at least one declared tool");
                }
                root.put("tool_choice", "required");
            }
            case ToolChoice.Specific specific -> {
                OpenAiRequestUnicode.requireWellFormedIdentity(specific.toolName(), "tool name");
                ToolSpec named = namedTool(tools, specific.toolName());
                if (named == null) {
                    throw new IllegalArgumentException(
                            "tool choice names a tool that is not declared in this request");
                }
                ObjectNode choice = root.putObject("tool_choice");
                boolean custom = OpenAiConstrainedSampling.resolveGrammar(named, compatibility.grammarTools()) != null;
                choice.put("type", custom ? "custom" : "function");
                choice.put("name", specific.toolName());
            }
            case ToolChoice.Mode.AUTO -> {
                // Already returned above; keep the switch exhaustive.
            }
        }
    }

    private static ToolSpec namedTool(List<ToolSpec> tools, String name) {
        for (ToolSpec spec : tools) {
            if (spec.name().equals(name)) {
                return spec;
            }
        }
        return null;
    }

    private static void applyPromptCache(
            ObjectNode root,
            PromptCacheOptions cache,
            OpenAiResponsesCompatibility compatibility
    ) {
        CacheRetention retention = cache.retention();
        switch (retention) {
            case NONE -> {
                // Omit cache body fields. The adapter also omits affinity headers.
            }
            case PROVIDER_DEFAULT -> {
                // Do not send a retention override. An explicit cache key is still
                // a request hint when the endpoint accepts prompt_cache_key.
                putCacheKey(root, cache.cacheKey(), compatibility);
            }
            case SHORT -> {
                if (!compatibility.promptCacheKey()) {
                    throw new IllegalArgumentException("short prompt cache is not supported by this endpoint");
                }
                putCacheKey(root, cache.cacheKey(), compatibility);
            }
            case LONG -> {
                if (!compatibility.longCacheRetention()) {
                    throw new IllegalArgumentException("long prompt cache is not supported by this endpoint");
                }
                putCacheKey(root, cache.cacheKey(), compatibility);
                root.put("prompt_cache_retention", "24h");
            }
        }
    }

    private static void putCacheKey(
            ObjectNode root,
            String cacheKey,
            OpenAiResponsesCompatibility compatibility
    ) {
        if (cacheKey == null) {
            return;
        }
        if (!compatibility.promptCacheKey()) {
            throw new IllegalArgumentException("prompt cache key is not supported by this endpoint");
        }
        OpenAiRequestUnicode.requireWellFormedIdentity(cacheKey, "prompt cache key");
        root.put("prompt_cache_key", OpenAiPromptCacheKeys.clamp(cacheKey));
    }
}
