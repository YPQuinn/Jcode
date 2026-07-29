package site.pplee.jcode.agentcore.support;

import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Message;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only {@link ModelClient} that serves scripted {@link Message.Assistant}
 * responses in FIFO order. Records every {@link ModelRequest} it receives so
 * tests can assert call counts and request payloads.
 *
 * <p>Does not contact any real model or network. When the response queue is
 * exhausted, {@code generate} returns a failed stage so the loop's error path
 * can be exercised.
 */
public final class ScriptedModelClient implements ModelClient {
    private final ConcurrentLinkedQueue<Message.Assistant> responses;
    private final List<ModelRequest> receivedRequests = new CopyOnWriteArrayList<>();

    public ScriptedModelClient(Message.Assistant... responses) {
        this.responses = new ConcurrentLinkedQueue<>(List.of(responses));
    }

    public List<ModelRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    @Override
    public CompletionStage<Message.Assistant> generate(ModelRequest request, CancellationSignal cancellation) {
        receivedRequests.add(request);
        var next = responses.poll();
        if (next == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("scripted responses exhausted"));
        }
        return CompletableFuture.completedFuture(next);
    }
}
