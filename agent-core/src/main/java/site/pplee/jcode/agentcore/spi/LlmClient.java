package site.pplee.jcode.agentcore.spi;

import site.pplee.jcode.agentcore.concurrent.CancellationToken;
import site.pplee.jcode.agentcore.model.AgentMessage;

import java.util.concurrent.CompletionStage;

/**
 * SPI for a model provider adapter. The adapter must aggregate any streaming
 * response into one final {@link AgentMessage.Assistant}, forward deltas via
 * {@link LlmEventSink}, and surface provider failures as a failed
 * {@link CompletionStage}; the loop converts these to an {@code ERROR}
 * assistant message and ends the run normally.
 */
public interface LlmClient {
    CompletionStage<AgentMessage.Assistant> generate(
            LlmRequest request,
            CancellationToken cancellation,
            LlmEventSink events
    );
}
