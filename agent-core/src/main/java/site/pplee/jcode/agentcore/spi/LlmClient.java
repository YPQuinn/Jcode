package site.pplee.jcode.agentcore.spi;

import site.pplee.jcode.agentcore.concurrent.CancellationToken;
import site.pplee.jcode.agentcore.model.AgentMessage;

import java.util.concurrent.CompletionStage;

public interface LlmClient {
    CompletionStage<AgentMessage.Assistant> generate(
            LlmRequest request,
            CancellationToken cancellation,
            LlmEventSink events
    );
}
