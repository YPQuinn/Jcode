package site.pplee.jcode.codingagent.session;

import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.codingagent.internal.SnapshotMapper;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Standard model transcript and historical configuration on one branch. */
public record SessionContext(
        List<AgentMessage> messages,
        Optional<ModelRef> model,
        Optional<ThinkingLevel> thinkingLevel
) {
    public SessionContext {
        messages = SnapshotMapper.agentMessages(
                Objects.requireNonNull(messages, "messages must not be null"));
        model = Objects.requireNonNull(model, "model must not be null");
        thinkingLevel = Objects.requireNonNull(thinkingLevel, "thinkingLevel must not be null");
    }

    @Override
    public List<AgentMessage> messages() {
        return SnapshotMapper.agentMessages(messages);
    }
}
