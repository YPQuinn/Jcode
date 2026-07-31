package site.pplee.jcode.agentcore.message;

import site.pplee.jcode.ai.message.Message;

import java.util.List;

/**
 * Projects an ordered {@link AgentMessage} view to standard {@link Message}s
 * for the model request. Runs after {@link ContextTransformer}, before
 * {@code ModelRequest} construction.
 *
 * <p><b>Synchronous</b>: the projector must not perform I/O or return an
 * incomplete stage. Asynchronous data fetching belongs in the transformer.
 *
 * <p><b>Output contract</b>: the returned list and every element must be
 * non-null. The loop defensively copies and validates the result. Failure
 * (synchronous throw) is normalized into a terminal {@code ERROR} (or
 * {@code ABORTED} if cancelled) assistant message; the model is not called.
 *
 * <p><b>No sequence validation</b>: provider-specific role ordering (e.g.
 * whether the last message must be a user, whether tool results must follow
 * an assistant) is not validated here. The projector only handles type-level
 * filtering and format conversion.
 */
@FunctionalInterface
public interface MessageProjector {
    /**
     * @param messages the transformed {@link AgentMessage} view
     * @return the standard {@link Message} list for the model request
     */
    List<Message> project(List<AgentMessage> messages);

    /**
     * Default projector: unwrap {@link StandardAgentMessage} to
     * {@link Message}, preserving order and duplicates, silently filtering
     * out unknown product messages.
     */
    static MessageProjector standard() {
        return messages -> messages.stream()
                .filter(StandardAgentMessage.class::isInstance)
                .map(StandardAgentMessage.class::cast)
                .map(StandardAgentMessage::message)
                .toList();
    }
}
