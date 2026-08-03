package site.pplee.jcode.agentcore.turn;

import site.pplee.jcode.agentcore.AgentContext;
import site.pplee.jcode.agentcore.message.AgentMessage;

import site.pplee.jcode.ai.message.Message;

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of one completed model turn, handed to the
 * {@link PrepareNextTurn} and {@link ShouldStopAfterTurn} hooks after the
 * {@code TurnCompleted} event has been emitted and awaited.
 *
 * <p>{@code context} is the authoritative run context at the moment the hook
 * is invoked. The {@link PrepareNextTurn} hook receives the pre-update
 * snapshot whose context contains the completed assistant message and all
 * tool results of the turn. The {@link ShouldStopAfterTurn} hook receives the
 * post-update snapshot: a {@link NextTurnUpdate} context replacement may have
 * pruned the transcript, so its context is not guaranteed to contain this
 * turn's messages. {@code newMessages} is the current run's append log — the
 * messages this run has completed and emitted so far.
 */
public record TurnContext(
        Message.Assistant assistant,
        List<Message.ToolResultMessage> toolResults,
        AgentContext context,
        List<AgentMessage> newMessages
) {
    public TurnContext {
        Objects.requireNonNull(assistant, "assistant must not be null");
        toolResults = List.copyOf(Objects.requireNonNull(toolResults, "toolResults must not be null"));
        Objects.requireNonNull(context, "context must not be null");
        newMessages = List.copyOf(Objects.requireNonNull(newMessages, "newMessages must not be null"));
    }
}
