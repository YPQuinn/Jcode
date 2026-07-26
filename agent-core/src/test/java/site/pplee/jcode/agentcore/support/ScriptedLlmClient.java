package site.pplee.jcode.agentcore.support;

import site.pplee.jcode.agentcore.concurrent.CancellationToken;
import site.pplee.jcode.agentcore.model.AgentMessage;
import site.pplee.jcode.agentcore.spi.LlmClient;
import site.pplee.jcode.agentcore.spi.LlmEventSink;
import site.pplee.jcode.agentcore.spi.LlmRequest;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only {@link LlmClient} that serves scripted {@link AgentMessage.Assistant}
 * responses in FIFO order. Records every {@link LlmRequest} it receives so
 * tests can assert call counts and request payloads.
 *
 * Does not contact any real model or network. When the response queue is
 * exhausted, {@code generate} returns a failed stage so the loop's error
 * path can be exercised.
 */
public final class ScriptedLlmClient implements LlmClient {
    private final ConcurrentLinkedQueue<AgentMessage.Assistant> responses;
    private final List<LlmRequest> receivedRequests = new CopyOnWriteArrayList<>();

    public ScriptedLlmClient(AgentMessage.Assistant... responses) {
        this.responses = new ConcurrentLinkedQueue<>(List.of(responses));
    }

    public List<LlmRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    @Override
    public CompletionStage<AgentMessage.Assistant> generate(
            LlmRequest request,
            CancellationToken cancellation,
            LlmEventSink events
    ) {
        receivedRequests.add(request);
        var next = responses.poll();
        if (next == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("scripted responses exhausted"));
        }
        return CompletableFuture.completedFuture(next);
    }
}
