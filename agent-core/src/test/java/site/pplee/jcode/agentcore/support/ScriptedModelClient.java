package site.pplee.jcode.agentcore.support;

import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only {@link ModelClient} that serves scripted {@link Message.Assistant}
 * responses in FIFO order. Records every {@link ModelRequest} it receives so
 * tests can assert call counts and request payloads.
 *
 * <p>Does not contact any real model or network. When the response queue is
 * exhausted, {@code stream} returns a stream that immediately terminates with
 * an {@link AssistantMessageEvent.Error} so the loop's error path can be
 * exercised.
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
    public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
        receivedRequests.add(request);
        var stream = new AssistantMessageStream();
        var next = responses.poll();
        if (next == null) {
            var error = new Message.Assistant(
                    List.of(), StopReason.ERROR, "scripted responses exhausted",
                    Usage.zero(), Instant.now());
            stream.push(new AssistantMessageEvent.Start(error));
            stream.push(new AssistantMessageEvent.Error(StopReason.ERROR, error));
        } else if (next.stopReason().isTerminalFailure()) {
            stream.push(new AssistantMessageEvent.Start(next));
            stream.push(new AssistantMessageEvent.Error(next.stopReason(), next));
        } else {
            stream.push(new AssistantMessageEvent.Start(next));
            stream.push(new AssistantMessageEvent.Done(next.stopReason(), next));
        }
        return stream;
    }
}
