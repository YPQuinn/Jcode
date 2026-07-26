package site.pplee.jcode.agentcore.model;

import java.util.List;
import java.util.Objects;

public record LoopResult(
        AgentContext context,
        List<AgentMessage> newMessages
) {
    public LoopResult {
        Objects.requireNonNull(context, "context must not be null");
        newMessages = List.copyOf(Objects.requireNonNull(newMessages, "newMessages must not be null"));
    }
}
