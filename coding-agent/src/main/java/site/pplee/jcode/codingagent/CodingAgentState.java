package site.pplee.jcode.codingagent;

import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.codingagent.internal.SnapshotMapper;

import java.util.Set;

/** Immutable product-state snapshot for a coding-agent session. */
public record CodingAgentState(
        boolean running,
        AgentMessage streamingMessage,
        Set<String> pendingToolCalls,
        String errorMessage
) {
    public CodingAgentState {
        streamingMessage = SnapshotMapper.nullableAgentMessage(streamingMessage);
        pendingToolCalls = pendingToolCalls == null ? Set.of() : Set.copyOf(pendingToolCalls);
    }

    @Override
    public AgentMessage streamingMessage() {
        return SnapshotMapper.nullableAgentMessage(streamingMessage);
    }

    @Override
    public Set<String> pendingToolCalls() {
        return Set.copyOf(pendingToolCalls);
    }
}
