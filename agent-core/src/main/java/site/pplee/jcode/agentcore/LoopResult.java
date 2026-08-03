package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.message.AgentMessage;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of one run: the full resulting context plus the append log of
 * messages this run completed and emitted.
 *
 * <p>{@code context} is the authoritative, persistent transcript state — it
 * includes any replacement context applied between turns. {@code newMessages}
 * is the run's append log: each message the runtime completed and emitted a
 * lifecycle for. A context replacement may prune earlier run messages, so
 * {@code newMessages} is not guaranteed to be a suffix or subset of
 * {@code context.messages()}.
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
