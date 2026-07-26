package site.pplee.jcode.agentcore.queue;

import site.pplee.jcode.agentcore.model.AgentMessage;

import java.util.List;

public interface PendingMessageSource {
    List<AgentMessage> drain();
}
