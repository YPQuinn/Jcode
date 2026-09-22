package site.pplee.jcode.codingagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.provider.DefaultModels;
import site.pplee.jcode.ai.provider.ModelProvider;
import site.pplee.jcode.ai.provider.ProviderAuth;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.codingagent.model.ModelAssemblyDiagnostic;
import site.pplee.jcode.codingagent.model.ModelProfile;
import site.pplee.jcode.codingagent.model.ModelSelection;
import site.pplee.jcode.codingagent.model.ProviderDefinition;
import site.pplee.jcode.codingagent.settings.CodingAgentSettings;
import site.pplee.jcode.codingagent.settings.SettingsOverrides;
import site.pplee.jcode.aiproviders.openai.OpenAiCredentials;
import site.pplee.jcode.aiproviders.openai.OpenAiModelCapabilities;
import site.pplee.jcode.aiproviders.openai.OpenAiResponsesCompatibility;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingAgentSessionFactoryTest {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ModelRef FIRST = new ModelRef("test", "responses", "first");
    private static final ModelRef SECOND = new ModelRef("test", "responses", "second");

    @TempDir
    Path directory;

    @Test
    void ownedDefinitionCreatesARealProviderAndOnlyPromptTouchesTheEndpoint() throws Exception {
        var authorization = new java.util.concurrent.atomic.AtomicReference<String>();
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("""
                    event: response.output_item.added
                    data: {"output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant","content":[],"status":"in_progress"}}

                    event: response.output_text.delta
                    data: {"output_index":0,"delta":"Hello"}

                    event: response.output_item.done
                    data: {"output_index":0,"item":{"type":"message","id":"msg_1","content":[{"type":"output_text","text":"Hello"}],"status":"completed"}}

                    event: response.completed
                    data: {"response":{"status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}

                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String secret = "owned-secret-value";
        try {
            var definition = new ProviderDefinition(
                    "owned", "Owned", "responses",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    OpenAiResponsesCompatibility.openaiNoSession(),
                    Optional.empty(), false,
                    List.of(new ProviderDefinition.DefinedModel(
                            "one", "One", OpenAiModelCapabilities.noReasoning(),
                            ModelProfile.empty())));
            var model = new ModelRef("owned", "responses", "one");
            var options = CodingAgentSessionOptions.builder(directory)
                    .providerDefinitions(List.of(definition))
                    .credentials(Map.of("owned", OpenAiCredentials.apiKey(secret)))
                    .settingsOverrides(explicit(model, ThinkingLevel.PROVIDER_DEFAULT))
                    .clock(CLOCK)
                    .build();

            var created = CodingAgentSessionFactory.inMemory(options);
            assertEquals(0, requests.get(), "assembly and selection must not contact the endpoint");
            assertFalse(created.toString().contains(secret));
            try (var session = created.session()) {
                session.prompt("hello").toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            assertEquals(1, requests.get());
            assertEquals("Bearer " + secret, authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void borrowedModelsCreateAnInMemorySessionWithTheResolvedSelection() throws Exception {
        var provider = new RecordingProvider(List.of(FIRST, SECOND));
        var options = options(provider, explicit(FIRST, ThinkingLevel.HIGH));

        var created = CodingAgentSessionFactory.inMemory(options);
        try (var session = created.session()) {
            assertEquals(FIRST, created.selection().selected());
            assertEquals(ModelSelection.Source.SDK, created.selection().source());
            assertEquals(ThinkingLevel.HIGH, created.selection().thinkingLevel());
            assertEquals(created.selection(), session.modelSelection());
            assertEquals(2, created.catalog().entries().size());

            session.prompt("hello").toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(FIRST, provider.requests().getFirst().model());
            assertEquals(ThinkingLevel.HIGH, provider.requests().getFirst().thinkingLevel());
        }
        assertFalse(provider.closed, "borrowed provider ownership stays with the caller");
    }

    @Test
    void newSessionWithoutAnExplicitOrDefaultModelDoesNotPickTheFirstCatalogEntry() {
        var provider = new RecordingProvider(List.of(FIRST));
        var options = options(provider, SettingsOverrides.none());

        var failure = assertThrows(SessionAssemblyException.class,
                () -> CodingAgentSessionFactory.inMemory(options));

        assertTrue(failure.getMessage().contains("no model was selected"));
        assertTrue(provider.requests().isEmpty());
    }

    @Test
    void openPrefersAvailableHistoricalModelAndFallsBackOnceWhenUnavailable() throws Exception {
        Path path;
        var initialProvider = new RecordingProvider(List.of(FIRST));
        var initial = CodingAgentSessionFactory.create(
                options(initialProvider, explicit(FIRST, ThinkingLevel.LOW)),
                directory.resolve("sessions"));
        try (var session = initial.session()) {
            session.prompt("persist").toCompletableFuture().get(2, TimeUnit.SECONDS);
            path = session.sessionFile().orElseThrow();
        }

        var userConfig = Files.createDirectory(directory.resolve("user-config"));
        Files.writeString(userConfig.resolve("settings.json"), """
                {"defaultModel":{"provider":"test","api":"responses","modelId":"second"}}
                """);
        var historicalProvider = new RecordingProvider(List.of(FIRST, SECOND));
        var restored = CodingAgentSessionFactory.open(
                options(historicalProvider, SettingsOverrides.none(), userConfig), path);
        try (var session = restored.session()) {
            assertEquals(FIRST, restored.selection().selected());
            assertEquals(ThinkingLevel.LOW, restored.selection().thinkingLevel());
            assertEquals(ModelSelection.Source.HISTORY, restored.selection().source());
        }

        var fallbackProvider = new RecordingProvider(List.of(SECOND));
        var fallback = CodingAgentSessionFactory.open(
                options(fallbackProvider, SettingsOverrides.none(), userConfig), path);
        try (var session = fallback.session()) {
            assertEquals(SECOND, fallback.selection().selected());
            assertEquals(ModelSelection.Source.DEFAULT, fallback.selection().source());
            assertTrue(fallback.modelDiagnostics().stream().anyMatch(diagnostic ->
                    diagnostic.code() == ModelAssemblyDiagnostic.Code.FALLBACK_SELECTED));
        }
    }

    @Test
    void failedOpenSelectionReleasesTheSessionWriter() throws Exception {
        Path path;
        var initialProvider = new RecordingProvider(List.of(FIRST));
        var initial = CodingAgentSessionFactory.create(
                options(initialProvider, explicit(FIRST, ThinkingLevel.LOW)),
                directory.resolve("sessions"));
        try (var session = initial.session()) {
            session.prompt("persist").toCompletableFuture().get(2, TimeUnit.SECONDS);
            path = session.sessionFile().orElseThrow();
        }

        var unavailable = new RecordingProvider(List.of(FIRST));
        assertThrows(SessionAssemblyException.class,
                () -> CodingAgentSessionFactory.open(
                        options(unavailable, explicit(SECOND, ThinkingLevel.HIGH)), path));

        var available = new RecordingProvider(List.of(FIRST));
        var reopened = CodingAgentSessionFactory.open(
                options(available, SettingsOverrides.none()), path);
        reopened.session().close();
    }

    @Test
    void failedSelectionDoesNotCreateASessionFile() throws Exception {
        var provider = new RecordingProvider(List.of(FIRST));
        var sessions = directory.resolve("sessions");
        var options = options(provider, explicit(SECOND, ThinkingLevel.PROVIDER_DEFAULT));

        assertThrows(SessionAssemblyException.class,
                () -> CodingAgentSessionFactory.create(options, sessions));

        assertFalse(Files.exists(sessions));
    }

    @Test
    void idleSwitchChangesTheNextRunButDoesNotPersistUntilAMessageCompletes() throws Exception {
        var provider = new RecordingProvider(List.of(FIRST, SECOND));
        var created = CodingAgentSessionFactory.create(
                options(provider, explicit(FIRST, ThinkingLevel.LOW)),
                directory.resolve("sessions"));
        try (var session = created.session()) {
            int before = session.history().entries().size();
            assertThrows(IllegalArgumentException.class,
                    () -> session.setModel(
                            new ModelRef("missing", "responses", "none"),
                            ThinkingLevel.HIGH));
            assertEquals(FIRST, session.modelSelection().selected());
            assertEquals(ThinkingLevel.LOW, session.modelSelection().thinkingLevel());

            var switched = session.setModel(SECOND, ThinkingLevel.HIGH);
            assertEquals(SECOND, switched.selected());
            assertEquals(before, session.history().entries().size());

            session.prompt("after switch").toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(SECOND, provider.requests().getFirst().model());
            assertEquals(ThinkingLevel.HIGH, provider.requests().getFirst().thinkingLevel());
            assertTrue(session.history().entries().size() > before);
        }
    }

    private CodingAgentSessionOptions options(
            RecordingProvider provider,
            SettingsOverrides overrides
    ) {
        return options(provider, overrides, null);
    }

    private CodingAgentSessionOptions options(
            RecordingProvider provider,
            SettingsOverrides overrides,
            Path userConfig
    ) {
        var builder = CodingAgentSessionOptions.builder(directory)
                .borrowedModels(new DefaultModels(List.of(provider)), Map.<ModelRef, ModelProfile>of())
                .settingsOverrides(overrides)
                .objectMapper(new ObjectMapper())
                .clock(CLOCK);
        if (userConfig != null) {
            builder.userConfigDirectory(userConfig);
        }
        return builder.build();
    }

    private static SettingsOverrides explicit(ModelRef model, ThinkingLevel thinking) {
        return SettingsOverrides.settings(CodingAgentSettings.builder()
                .defaultModel(model)
                .defaultThinkingLevel(thinking)
                .build());
    }

    private static final class RecordingProvider implements ModelProvider, AutoCloseable {
        private final List<Model> models;
        private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
        private boolean closed;

        private RecordingProvider(List<ModelRef> refs) {
            models = refs.stream()
                    .map(ref -> new Model(ref.provider(), ref.api(), ref.modelId(), ref.modelId()))
                    .toList();
        }

        private List<ModelRequest> requests() {
            return List.copyOf(requests);
        }

        @Override
        public String id() {
            return "test";
        }

        @Override
        public String name() {
            return "Test";
        }

        @Override
        public Optional<URI> baseUrl() {
            return Optional.empty();
        }

        @Override
        public ProviderAuth auth() {
            return ProviderAuth.of(true, "");
        }

        @Override
        public List<Model> models() {
            return models;
        }

        @Override
        public boolean supports(ModelRef ref) {
            return models.stream().anyMatch(model -> model.toRef().equals(ref));
        }

        @Override
        public AssistantMessageStream stream(
                ModelRequest request,
                CancellationSignal cancellation
        ) {
            requests.add(request);
            var message = new Message.Assistant(
                    List.of(new Content.Text("ok")), StopReason.STOP, null,
                    Usage.zero(), NOW, request.model());
            var stream = new AssistantMessageStream();
            stream.push(new AssistantMessageEvent.Start(message));
            stream.push(new AssistantMessageEvent.Done(StopReason.STOP, message));
            return stream;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
