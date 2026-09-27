package site.pplee.jcode.codingagent.event;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import java.util.List;
import java.util.Optional;

/** Narrow product projection of runtime progress for application clients. */
public sealed interface CodingAgentDisplayEvent
        permits CodingAgentDisplayEvent.RunStarted,
        CodingAgentDisplayEvent.MessageChange, CodingAgentDisplayEvent.ToolChange {
    int MAX_MESSAGE_CHARS = 16_385;
    int MAX_TOOL_CHARS = 8_193;

    enum Phase { STARTED, SNAPSHOT, COMPLETED }

    /** The underlying loop has begun executing this product run. */
    record RunStarted() implements CodingAgentDisplayEvent { }

    /** Full current text replaces the previous partial state for this message. */
    record MessageChange(Phase phase, String role, String text, String inputId) implements CodingAgentDisplayEvent { }

    /** Tool updates are snapshots, including live process tails. */
    record ToolChange(
            Phase phase,
            String toolCallId,
            String toolName,
            String text,
            boolean error
    ) implements CodingAgentDisplayEvent { }

    static Optional<CodingAgentDisplayEvent> from(CodingAgentEvent event) {
        if (!(event instanceof CodingAgentEvent.RuntimeEvent runtime)) {
            return Optional.empty();
        }
        return switch (runtime.event()) {
            case AgentEvent.AgentStarted ignored -> Optional.of(new RunStarted());
            case AgentEvent.MessageStarted started -> Optional.of(
                    new MessageChange(Phase.STARTED, role(started.message()),
                            text(started.message()), null));
            case AgentEvent.MessageUpdated updated -> Optional.of(
                    new MessageChange(Phase.SNAPSHOT, role(updated.message()),
                            text(updated.delta().partial().content(), MAX_MESSAGE_CHARS, false), null));
            case AgentEvent.MessageCompleted completed -> Optional.of(
                    new MessageChange(Phase.COMPLETED, role(completed.message()),
                            text(completed.message()), completed.inputId()));
            case AgentEvent.ToolStarted started -> Optional.of(
                    new ToolChange(Phase.STARTED, started.call().id(),
                            started.call().name(), "", false));
            case AgentEvent.ToolUpdate update -> update.update() instanceof Content.Text
                    ? Optional.of(new ToolChange(Phase.SNAPSHOT, update.call().id(),
                            update.call().name(),
                            text(List.of(update.update()), MAX_TOOL_CHARS, true), false))
                    : Optional.empty();
            case AgentEvent.ToolCompleted completed -> Optional.of(
                    new ToolChange(Phase.COMPLETED, completed.result().toolCallId(),
                            completed.result().toolName(),
                            text(completed.result().content(), MAX_TOOL_CHARS, true),
                            completed.result().error()));
            default -> Optional.empty();
        };
    }

    private static String role(AgentMessage message) {
        if (!(message instanceof StandardAgentMessage standard)) {
            return "CUSTOM";
        }
        return switch (standard.message()) {
            case Message.User ignored -> "USER";
            case Message.Assistant ignored -> "ASSISTANT";
            case Message.ToolResultMessage ignored -> "TOOL_RESULT";
        };
    }

    private static String text(AgentMessage message) {
        if (!(message instanceof StandardAgentMessage standard)) {
            return "";
        }
        return switch (standard.message()) {
            case Message.User user -> text(user.content(), MAX_MESSAGE_CHARS, false);
            case Message.Assistant assistant -> text(assistant.content(), MAX_MESSAGE_CHARS, false);
            case Message.ToolResultMessage result -> text(result.content(), MAX_MESSAGE_CHARS, false);
        };
    }

    private static String text(List<Content> content, int limit, boolean tail) {
        var result = new StringBuilder();
        for (var part : content) {
            if (part instanceof Content.Text segment) {
                String value = segment.text();
                if (tail) {
                    if (value.length() >= limit) {
                        result.setLength(0);
                        result.append(value, value.length() - limit, value.length());
                    } else {
                        result.append(value);
                        if (result.length() > limit) {
                            result.delete(0, result.length() - limit);
                        }
                    }
                } else if (result.length() < limit) {
                    result.append(value, 0, Math.min(value.length(), limit - result.length()));
                }
            }
        }
        return result.toString();
    }
}
