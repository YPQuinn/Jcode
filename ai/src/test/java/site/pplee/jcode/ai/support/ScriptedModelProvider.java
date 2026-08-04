package site.pplee.jcode.ai.support;

import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.provider.ModelProvider;
import site.pplee.jcode.ai.provider.ModelsRefreshContext;
import site.pplee.jcode.ai.provider.ProviderAuth;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Test-only {@link ModelProvider} with scripted identity, catalog, auth,
 * refresh and stream behavior. Records every request it receives. Does not
 * contact any real model or network. Non-final so tests can override
 * individual behaviors (e.g. a throwing {@link #supports}).
 */
public class ScriptedModelProvider implements ModelProvider {
    private final String id;
    private final String name;
    private final List<Model> models;
    private final ProviderAuth auth;
    private final Function<ModelRequest, AssistantMessageStream> responder;
    private final Supplier<CompletionStage<Void>> refreshAction;
    private final List<ModelRequest> receivedRequests = new CopyOnWriteArrayList<>();

    public ScriptedModelProvider(String id, String name, List<Model> models) {
        this(id, name, models, ProviderAuth.of(true, ""),
                request -> okStream(),
                () -> CompletableFuture.completedFuture(null));
    }

    public ScriptedModelProvider(
            String id,
            String name,
            List<Model> models,
            ProviderAuth auth,
            Function<ModelRequest, AssistantMessageStream> responder,
            Supplier<CompletionStage<Void>> refreshAction
    ) {
        this.id = id;
        this.name = name;
        this.models = List.copyOf(models);
        this.auth = auth;
        this.responder = responder;
        this.refreshAction = refreshAction;
    }

    /** A stream that immediately completes with a successful STOP assistant. */
    public static AssistantMessageStream okStream() {
        var stream = new AssistantMessageStream();
        var message = Message.Assistant.of(List.of(new Content.Text("ok")), StopReason.STOP, Instant.now());
        stream.push(new AssistantMessageEvent.Start(message));
        stream.push(new AssistantMessageEvent.Done(StopReason.STOP, message));
        return stream;
    }

    /** Requests received by this provider so far. */
    public List<ModelRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Optional<URI> baseUrl() {
        return Optional.empty();
    }

    @Override
    public ProviderAuth auth() {
        return auth;
    }

    @Override
    public List<Model> models() {
        return models;
    }

    @Override
    public boolean supports(ModelRef ref) {
        return id.equals(ref.provider())
                && models.stream().anyMatch(model -> model.toRef().equals(ref));
    }

    @Override
    public CompletionStage<Void> refreshModels(ModelsRefreshContext context) {
        return refreshAction.get();
    }

    @Override
    public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
        receivedRequests.add(request);
        return responder.apply(request);
    }
}
