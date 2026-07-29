package site.pplee.jcode.ai.client;

import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.tool.ToolSpec;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral request boundary from a caller to a {@link ModelClient}:
 * which model (by stable {@link ModelRef}), the system prompt, the
 * <em>standard</em> {@link Message} history (already projected from any
 * caller-specific transcript shape), and the declarable {@link ToolSpec}s.
 *
 * <p>Carries only {@code ai} types — never {@code AgentMessage} or executable
 * tools — so the model-call seam stays provider-neutral and free of
 * {@code agent-core} coupling.
 */
public record ModelRequest(
        ModelRef model,
        String systemPrompt,
        List<Message> messages,
        List<ToolSpec> tools
) {
    public ModelRequest {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
    }
}
