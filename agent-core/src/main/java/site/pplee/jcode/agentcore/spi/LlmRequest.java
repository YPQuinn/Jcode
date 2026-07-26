package site.pplee.jcode.agentcore.spi;

import site.pplee.jcode.agentcore.model.AgentMessage;
import site.pplee.jcode.agentcore.model.ModelRef;

import java.util.List;
import java.util.Objects;

public record LlmRequest(
        ModelRef model,
        String systemPrompt,
        List<AgentMessage> messages,
        List<AgentTool<?>> tools
) {
    public LlmRequest {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
    }
}
