package site.pplee.jcode.codingagent.internal;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.agentcore.LoopResult;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.codingagent.CodingAgentRunResult;
import site.pplee.jcode.codingagent.message.CustomAgentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Internal structural copier for product snapshots containing mutable JSON trees. */
public final class SnapshotMapper {
    private SnapshotMapper() {
    }

    public static AgentMessage nullableAgentMessage(AgentMessage message) {
        return message == null ? null : agentMessage(message);
    }

    public static AgentMessage agentMessage(AgentMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        if (message instanceof StandardAgentMessage standard) {
            return StandardAgentMessage.of(message(standard.message()));
        }
        if (message instanceof CustomAgentMessage custom) {
            return new CustomAgentMessage(
                    custom.extensionId(), custom.customType(), custom.content(),
                    custom.details(), custom.display(), custom.timestamp());
        }
        throw new IllegalArgumentException(
                "unsupported product message type: " + message.getClass().getName());
    }

    public static List<AgentMessage> agentMessages(List<? extends AgentMessage> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        var copy = new ArrayList<AgentMessage>(messages.size());
        for (var message : messages) {
            copy.add(agentMessage(message));
        }
        return List.copyOf(copy);
    }

    public static Message message(Message message) {
        Objects.requireNonNull(message, "message must not be null");
        return switch (message) {
            case Message.User user -> new Message.User(contents(user.content()), user.timestamp());
            case Message.Assistant assistant -> assistant(assistant);
            case Message.ToolResultMessage toolResult -> new Message.ToolResultMessage(
                    toolResult.toolCallId(), toolResult.toolName(), contents(toolResult.content()),
                    toolResult.error(), toolResult.timestamp());
        };
    }

    public static Message.Assistant assistant(Message.Assistant assistant) {
        Objects.requireNonNull(assistant, "assistant must not be null");
        return new Message.Assistant(
                contents(assistant.content()), assistant.stopReason(), assistant.errorMessage(),
                assistant.usage(), assistant.timestamp(), assistant.sourceModel(), assistant.metadata());
    }

    public static List<Content> contents(List<? extends Content> contents) {
        Objects.requireNonNull(contents, "contents must not be null");
        return contents.stream().map(SnapshotMapper::content).toList();
    }

    public static Content content(Content content) {
        Objects.requireNonNull(content, "content must not be null");
        return switch (content) {
            case Content.Text text -> new Content.Text(text.text(), text.replayState());
            case Content.Thinking thinking -> new Content.Thinking(thinking.text(), thinking.replayState());
            case Content.ToolCall call -> toolCall(call);
            case Content.Image image -> image;
        };
    }

    public static Content.ToolCall toolCall(Content.ToolCall call) {
        Objects.requireNonNull(call, "call must not be null");
        return new Content.ToolCall(call.id(), call.name(), call.arguments().deepCopy());
    }

    public static AssistantMessageEvent assistantEvent(AssistantMessageEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return switch (event) {
            case AssistantMessageEvent.Start start ->
                    new AssistantMessageEvent.Start(assistant(start.partial()));
            case AssistantMessageEvent.TextStart start ->
                    new AssistantMessageEvent.TextStart(start.contentIndex(), assistant(start.partial()));
            case AssistantMessageEvent.TextDelta delta ->
                    new AssistantMessageEvent.TextDelta(
                            delta.contentIndex(), delta.delta(), assistant(delta.partial()));
            case AssistantMessageEvent.TextEnd end ->
                    new AssistantMessageEvent.TextEnd(
                            end.contentIndex(), end.content(), assistant(end.partial()));
            case AssistantMessageEvent.ThinkingStart start ->
                    new AssistantMessageEvent.ThinkingStart(start.contentIndex(), assistant(start.partial()));
            case AssistantMessageEvent.ThinkingDelta delta ->
                    new AssistantMessageEvent.ThinkingDelta(
                            delta.contentIndex(), delta.delta(), assistant(delta.partial()));
            case AssistantMessageEvent.ThinkingEnd end ->
                    new AssistantMessageEvent.ThinkingEnd(
                            end.contentIndex(), end.content(), assistant(end.partial()));
            case AssistantMessageEvent.ToolCallStart start ->
                    new AssistantMessageEvent.ToolCallStart(start.contentIndex(), assistant(start.partial()));
            case AssistantMessageEvent.ToolCallDelta delta ->
                    new AssistantMessageEvent.ToolCallDelta(
                            delta.contentIndex(), delta.delta(), assistant(delta.partial()));
            case AssistantMessageEvent.ToolCallEnd end ->
                    new AssistantMessageEvent.ToolCallEnd(
                            end.contentIndex(), toolCall(end.toolCall()), assistant(end.partial()));
            case AssistantMessageEvent.Done done ->
                    new AssistantMessageEvent.Done(done.reason(), assistant(done.message()));
            case AssistantMessageEvent.Error error ->
                    new AssistantMessageEvent.Error(error.reason(), assistant(error.error()));
        };
    }

    public static AgentEvent runtimeEvent(AgentEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return switch (event) {
            case AgentEvent.AgentStarted ignored -> new AgentEvent.AgentStarted();
            case AgentEvent.TurnStarted ignored -> new AgentEvent.TurnStarted();
            case AgentEvent.MessageStarted started ->
                    new AgentEvent.MessageStarted(agentMessage(started.message()));
            case AgentEvent.MessageUpdated updated ->
                    new AgentEvent.MessageUpdated(
                            agentMessage(updated.message()), assistantEvent(updated.delta()));
            case AgentEvent.MessageCompleted completed ->
                    new AgentEvent.MessageCompleted(agentMessage(completed.message()));
            case AgentEvent.ToolStarted started ->
                    new AgentEvent.ToolStarted(toolCall(started.call()));
            case AgentEvent.ToolUpdate update ->
                    new AgentEvent.ToolUpdate(toolCall(update.call()), content(update.update()));
            case AgentEvent.ToolCompleted completed ->
                    new AgentEvent.ToolCompleted((Message.ToolResultMessage) message(completed.result()));
            case AgentEvent.TurnCompleted completed ->
                    new AgentEvent.TurnCompleted(
                            assistant(completed.assistant()),
                            completed.toolResults().stream()
                                    .map(result -> (Message.ToolResultMessage) message(result))
                                    .toList());
            case AgentEvent.AgentCompleted ignored ->
                    throw new IllegalArgumentException("AgentCompleted must be projected to RunCompleted");
        };
    }

    public static CodingAgentRunResult runResult(LoopResult result) {
        Objects.requireNonNull(result, "result must not be null");
        Message.Assistant finalAssistant = null;
        var messages = result.newMessages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            var message = messages.get(index);
            if (message instanceof StandardAgentMessage standard
                    && standard.message() instanceof Message.Assistant assistant) {
                finalAssistant = assistant;
                break;
            }
        }
        if (finalAssistant == null) {
            throw new IllegalStateException("run completed without an assistant message");
        }
        return new CodingAgentRunResult(messages, finalAssistant);
    }

    public static CodingAgentRunResult runResult(CodingAgentRunResult result) {
        Objects.requireNonNull(result, "result must not be null");
        return new CodingAgentRunResult(result.newMessages(), result.finalMessage());
    }
}
