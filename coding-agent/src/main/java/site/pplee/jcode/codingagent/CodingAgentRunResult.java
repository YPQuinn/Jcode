package site.pplee.jcode.codingagent;

import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.codingagent.internal.SnapshotMapper;

import java.util.List;
import java.util.Objects;

/** Immutable public outcome of one coding-agent run. */
public record CodingAgentRunResult(
        List<AgentMessage> newMessages,
        Message.Assistant finalMessage
) {
    public CodingAgentRunResult {
        newMessages = SnapshotMapper.agentMessages(newMessages);
        finalMessage = SnapshotMapper.assistant(
                Objects.requireNonNull(finalMessage, "finalMessage must not be null"));
    }

    @Override
    public List<AgentMessage> newMessages() {
        return SnapshotMapper.agentMessages(newMessages);
    }

    @Override
    public Message.Assistant finalMessage() {
        return SnapshotMapper.assistant(finalMessage);
    }

    /** True when the final assistant represents active cancellation. */
    public boolean aborted() {
        return finalMessage.stopReason() == StopReason.ABORTED;
    }
}
