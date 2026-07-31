package site.pplee.jcode.agentcore.message;

/**
 * Open, extensible transcript message interface. Product layers may implement
 * it directly to carry custom messages (artifacts, notifications, audit
 * records, …) in the {@code Agent} transcript; only the model-call seam projects
 * them back to {@link site.pplee.jcode.ai.message.Message}.
 *
 * <p>TypeScript expresses an open transcript type via declaration merging
 * ({@code type AgentMessage = Message | CustomAgentMessages[keyof CustomAgentMessages]}).
 * Java has no such merging, so the extension point is a plain (implicitly
 * non-sealed) interface plus a standard bridge ({@link StandardAgentMessage}).
 *
 * <p><b>Not</b> sealed: any product module may add an {@code AgentMessage}.
 * The default {@link MessageProjector#standard()} unwraps
 * {@link StandardAgentMessage} and filters out unknown product messages
 * (keep {@code user}/{@code assistant}/{@code toolResult}, drop the rest).
 * A {@link ContextTransformer} may prune or inject messages into a
 * request-local view before projection; neither modifies the transcript.
 */
public interface AgentMessage {
    // Intentionally empty: a marker extension point. Implementations include
    // StandardAgentMessage (wrapping an ai.Message) and, in the future,
    // coding-agent's own message types.
}
