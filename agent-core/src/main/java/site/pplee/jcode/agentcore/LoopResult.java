package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.message.AgentMessage;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of one run: the full resulting context plus only the messages
 * this run appended.
 */
public record LoopResult(
        AgentContext context,
        List<AgentMessage> newMessages
) {
    public LoopResult {
        Objects.requireNonNull(context, "context must not be null");
        newMessages = List.copyOf(Objects.requireNonNull(newMessages, "newMessages must not be null"));
    }
}
