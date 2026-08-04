package site.pplee.jcode.ai.provider;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.ai.support.MutableCancellationSignal;
import site.pplee.jcode.ai.support.ScriptedModelProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the provider runtime collection: catalog validation,
 * dispatch, snapshot semantics, auth status and refresh failure handling.
 */
class ModelsRuntimeTest {
    private static final Model OPENAI_GPT = new Model("openai", "openai-responses", "gpt-4o", "gpt-4o");
    private static final Model ANTHROPIC_SONNET = new Model("anthropic", "anthropic-messages", "claude-sonnet-4", "claude-sonnet-4");

    private static ModelRequest request(ModelRef ref) {
        return new ModelRequest(ref, "sys", List.of(), List.of());
    }

    @Test
    void streamDispatchesToOwningProvider() throws Exception {
        var openAi = new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT));
        var anthropic = new ScriptedModelProvider("anthropic", "Anthropic", List.of(ANTHROPIC_SONNET));
        var models = new DefaultModels(List.of(openAi, anthropic));

        var stream = models.stream(request(ANTHROPIC_SONNET.toRef()), new MutableCancellationSignal());
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        var done = assertInstanceOf(AssistantMessageEvent.Done.class, stream.take());
        assertEquals(StopReason.STOP, done.reason());
        assertEquals(1, anthropic.receivedRequests().size());
        assertEquals(0, openAi.receivedRequests().size());
    }

    @Test
    void unknownProviderProducesStartErrorStream() throws Exception {
        var models = new DefaultModels(
                List.of(new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT))));

        var stream = models.stream(
                request(new ModelRef("nope", "openai-responses", "x")), new MutableCancellationSignal());
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, stream.take());
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("unknown provider"));
    }

    @Test
    void unsupportedModelProducesStartErrorStream() throws Exception {
        var models = new DefaultModels(
                List.of(new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT))));

        var stream = models.stream(
                request(new ModelRef("openai", "openai-responses", "gpt-999")), new MutableCancellationSignal());
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, stream.take());
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("does not support"));
    }

    @Test
    void collectionsReturnImmutableSnapshots() {
        var openAi = new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT));
        var models = new DefaultModels(List.of(openAi));

        assertThrows(UnsupportedOperationException.class, () -> models.providers().add(openAi));
        assertThrows(UnsupportedOperationException.class, () -> models.models().add(OPENAI_GPT));
        assertEquals(Optional.of(openAi), models.provider("openai"));
        assertTrue(models.provider("nope").isEmpty());
        assertEquals(List.of(OPENAI_GPT), models.models("openai"));
        assertEquals(List.of(), models.models("nope"));
        assertEquals(Optional.of(OPENAI_GPT), models.model(OPENAI_GPT.toRef()));
        assertTrue(models.model(new ModelRef("openai", "openai-responses", "nope")).isEmpty());
    }

    @Test
    void rejectsDuplicateProviderId() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultModels(List.of(
                new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT)),
                new ScriptedModelProvider("openai", "OpenAI Two", List.of()))));
    }

    @Test
    void rejectsProviderModelMismatch() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultModels(List.of(
                new ScriptedModelProvider("openai", "OpenAI", List.of(ANTHROPIC_SONNET)))));
    }

    @Test
    void rejectsDuplicateModelRefInCatalog() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultModels(List.of(
                new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT, OPENAI_GPT)))));
    }

    @Test
    void modelsIsUsableAsModelClient() throws Exception {
        var models = new DefaultModels(
                List.of(new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT))));
        ModelClient client = models;

        var stream = client.stream(request(OPENAI_GPT.toRef()), new MutableCancellationSignal());
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        assertInstanceOf(AssistantMessageEvent.Done.class, stream.take());
    }

    @Test
    void checkAuthReportsConfiguredAndUnknownProviders() {
        var unconfigured = new ScriptedModelProvider("unconfigured", "U", List.of(),
                ProviderAuth.of(false, "missing key"),
                request -> ScriptedModelProvider.okStream(),
                () -> CompletableFuture.completedFuture(null));
        var models = new DefaultModels(List.of(
                new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT)),
                unconfigured));

        var openAi = models.checkAuth("openai", new MutableCancellationSignal()).toCompletableFuture().join();
        assertTrue(openAi.configured());
        assertEquals("", openAi.diagnostic());

        var missing = models.checkAuth("unconfigured", new MutableCancellationSignal()).toCompletableFuture().join();
        assertFalse(missing.configured());
        assertEquals("missing key", missing.diagnostic());

        var unknown = models.checkAuth("nope", new MutableCancellationSignal()).toCompletableFuture().join();
        assertFalse(unknown.configured());
        assertTrue(unknown.diagnostic().contains("unknown"));
    }

    @Test
    void refreshCollectsFailuresWithoutExceptionalFuture() {
        var failing = new ScriptedModelProvider("failing", "F", List.of(),
                ProviderAuth.of(true, ""),
                request -> ScriptedModelProvider.okStream(),
                () -> CompletableFuture.failedFuture(new RuntimeException("refresh failed")));
        var models = new DefaultModels(List.of(failing));

        var result = models.refresh(new ModelsRefreshContext(new MutableCancellationSignal()))
                .toCompletableFuture().join();
        assertFalse(result.successful());
        assertEquals(1, result.failures().size());
        assertEquals("failing", result.failures().get(0).providerId());
    }

    @Test
    void refreshSucceedsWhenNoProviderFails() {
        var models = new DefaultModels(
                List.of(new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT))));

        var result = models.refresh(new ModelsRefreshContext(new MutableCancellationSignal()))
                .toCompletableFuture().join();
        assertTrue(result.successful());
        assertTrue(result.failures().isEmpty());
    }

    @Test
    void copyOnWriteModelsMutatesExplicitly() throws Exception {
        var models = new CopyOnWriteModels();
        assertTrue(models.providers().isEmpty());

        var openAi = new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT));
        models.setProvider(openAi);
        assertEquals(1, models.providers().size());

        var replacement = new ScriptedModelProvider("openai", "OpenAI Two", List.of(OPENAI_GPT));
        models.setProvider(replacement);
        assertEquals("OpenAI Two", models.provider("openai").orElseThrow().name());

        models.deleteProvider("openai");
        assertTrue(models.provider("openai").isEmpty());
        models.deleteProvider("openai"); // idempotent

        models.setProvider(openAi);
        models.clearProviders();
        assertTrue(models.providers().isEmpty());
    }

    @Test
    void copyOnWriteModelsStreamUsesCallStartSnapshot() throws Exception {
        var models = new CopyOnWriteModels();
        var openAi = new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT));
        models.setProvider(openAi);

        var stream = models.stream(request(OPENAI_GPT.toRef()), new MutableCancellationSignal());
        // Mutate after dispatch: the already-created stream is unaffected.
        models.clearProviders();

        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        var done = assertInstanceOf(AssistantMessageEvent.Done.class, stream.take());
        assertEquals(StopReason.STOP, done.reason());
    }

    @Test
    void copyOnWriteModelsRejectsInvalidProvider() {
        var models = new CopyOnWriteModels();
        assertThrows(IllegalArgumentException.class,
                () -> models.setProvider(new ScriptedModelProvider("openai", "OpenAI", List.of(ANTHROPIC_SONNET))));
    }

    @Test
    void streamWrapsThrowingSupportsIntoStartError() throws Exception {
        var throwing = new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT)) {
            @Override
            public boolean supports(ModelRef ref) {
                throw new IllegalStateException("supports boom");
            }
        };
        var models = new DefaultModels(List.of(throwing));

        var stream = models.stream(request(OPENAI_GPT.toRef()), new MutableCancellationSignal());
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, stream.take());
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("supports boom"));
    }

    @Test
    void streamWrapsThrowingProviderStreamIntoStartError() throws Exception {
        var throwing = new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT),
                ProviderAuth.of(true, ""),
                request -> {
                    throw new IllegalStateException("stream boom");
                },
                () -> CompletableFuture.completedFuture(null));
        var models = new DefaultModels(List.of(throwing));

        var stream = models.stream(request(OPENAI_GPT.toRef()), new MutableCancellationSignal());
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, stream.take());
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("stream boom"));
    }

    @Test
    void refreshCollectsSynchronousThrowAndNullStage() {
        var syncThrow = new ScriptedModelProvider("sync", "Sync", List.of(),
                ProviderAuth.of(true, ""),
                request -> ScriptedModelProvider.okStream(),
                () -> {
                    throw new IllegalStateException("sync boom");
                });
        var nullStage = new ScriptedModelProvider("null", "Null", List.of(),
                ProviderAuth.of(true, ""),
                request -> ScriptedModelProvider.okStream(),
                () -> null);
        var models = new DefaultModels(List.of(syncThrow, nullStage));

        var result = models.refresh(new ModelsRefreshContext(new MutableCancellationSignal()))
                .toCompletableFuture().join();
        assertFalse(result.successful());
        assertEquals(2, result.failures().size());
    }

    @Test
    void modelsStringReturnsImmutableSnapshotForMutableProviderList() {
        var mutableProvider = new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT)) {
            @Override
            public List<Model> models() {
                return new ArrayList<>(super.models());
            }
        };
        var models = new DefaultModels(List.of(mutableProvider));

        var snapshot = models.models("openai");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(OPENAI_GPT));
        assertThrows(UnsupportedOperationException.class, () -> models.models().add(OPENAI_GPT));
    }

    @Test
    void providerModelLookupIsScopedToRequestedProvider() {
        var openAi = new ScriptedModelProvider("openai", "OpenAI", List.of(OPENAI_GPT));
        var other = new ScriptedModelProvider("other", "Other", List.of(
                new Model("other", "openai-responses", "gpt-4o", "gpt-4o")));
        var models = new DefaultModels(List.of(openAi, other));

        // Same model id served by a different provider must not match a ref for "openai".
        assertTrue(models.model(OPENAI_GPT.toRef()).isPresent());
        assertTrue(models.model(new ModelRef("other", "openai-responses", "gpt-4o")).isPresent());
        assertTrue(models.model(new ModelRef("openai", "openai-responses", "claude-sonnet-4")).isEmpty());
    }
}
