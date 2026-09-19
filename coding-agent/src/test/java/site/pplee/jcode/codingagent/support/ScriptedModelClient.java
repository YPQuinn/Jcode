package site.pplee.jcode.codingagent.support;

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
import java.util.function.Function;

public final class ScriptedModelClient implements ModelClient {
    private final ConcurrentLinkedQueue<Function<ModelRequest, Message.Assistant>> responses;
    private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();

    @SafeVarargs
    public ScriptedModelClient(Function<ModelRequest, Message.Assistant>... responses) {
        this.responses = new ConcurrentLinkedQueue<>(List.of(responses));
    }

    public List<ModelRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
        requests.add(request);
        var stream = new AssistantMessageStream();
        var response = responses.poll();
        Message.Assistant message;
        if (response == null) {
            message = new Message.Assistant(List.of(), StopReason.ERROR,
                    "scripted responses exhausted", Usage.zero(), Instant.EPOCH);
        } else {
            message = response.apply(request);
        }
        stream.push(new AssistantMessageEvent.Start(message));
        if (message.stopReason().isTerminalFailure()) {
            stream.push(new AssistantMessageEvent.Error(message.stopReason(), message));
        } else {
            stream.push(new AssistantMessageEvent.Done(message.stopReason(), message));
        }
        return stream;
    }
}
