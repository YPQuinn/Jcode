package site.pplee.jcode.agentcore;

import site.pplee.jcode.agentcore.message.AgentMessage;

import java.util.Set;

/**
 * Immutable real-time snapshot of the agent's runtime state during and
 * between runs. {@link Agent#state()} returns a fresh snapshot after each
 * event is reduced.
 *
 * <p>The persistent transcript lives in {@link AgentContext}; this type
 * surfaces streaming-only fields that change during a run:
 *
 * <ul>
 *   <li>{@link #streaming()} — true while a run is active
 *   <li>{@link #streamingMessage()} — the current partial assistant message,
 *       or {@code null} when not streaming an assistant response
 *   <li>{@link #pendingToolCalls()} — tool-call ids currently executing
 *   <li>{@link #errorMessage()} — the most recent error/abort message, or
 *       {@code null} when the last turn succeeded
 * </ul>
 */
public record AgentState(
        AgentContext context,
        boolean streaming,
        AgentMessage streamingMessage,
        Set<String> pendingToolCalls,
        String errorMessage
) {
    public AgentState {
        // context is allowed to be null for the pre-run sentinel
        pendingToolCalls = pendingToolCalls == null ? Set.of() : Set.copyOf(pendingToolCalls);
    }

    /** Initial non-streaming state for a fresh agent. */
    public static AgentState initial(AgentContext context) {
        return new AgentState(context, false, null, Set.of(), null);
    }
}
