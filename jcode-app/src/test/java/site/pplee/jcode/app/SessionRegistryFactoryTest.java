package site.pplee.jcode.app;

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
import site.pplee.jcode.ai.provider.DefaultModels;
import site.pplee.jcode.ai.provider.ModelProvider;
import site.pplee.jcode.ai.provider.ProviderAuth;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.codingagent.CodingAgentSessionOptions;
import site.pplee.jcode.codingagent.InputDeliveryMode;
import site.pplee.jcode.codingagent.settings.CodingAgentSettings;
import site.pplee.jcode.codingagent.settings.SettingsOverrides;
import site.pplee.jcode.protocol.RunCommand;
import site.pplee.jcode.protocol.RunKind;
import site.pplee.jcode.protocol.RunStatus;
import site.pplee.jcode.protocol.ErrorCode;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SessionRegistryFactoryTest {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "one");

    @TempDir
    Path directory;

    @Test
    void settingsAssemblyKeepsObservationAndBorrowedModelOwnership() throws Exception {
        var provider = new BorrowedProvider();
        var options = CodingAgentSessionOptions.builder(directory)
                .borrowedModels(new DefaultModels(List.of(provider)), Map.of())
                .settingsOverrides(SettingsOverrides.settings(CodingAgentSettings.builder()
                        .defaultModel(MODEL).build()))
                .inputDeliveryMode(InputDeliveryMode.RUN_SCOPED)
                .build();
        var registry = new SessionRegistry();
        var created = registry.create(options, directory.resolve("sessions"),
                ApprovalSettings.none());
        Path file = created.sessionFile().orElseThrow();
        var before = created.snapshot();
        created.start(new RunCommand("command-1", "run-1", RunKind.PROMPT, "hello", null));
        assertEquals(RunStatus.COMPLETED, created.settled("run-1")
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join().status());
        assertTrue(created.snapshot().cursor().seq() > before.cursor().seq());
        assertFalse(created.snapshot().messages().isEmpty(),
                "factory-created sessions must publish product display events");
        registry.close(created.sessionId());
        assertFalse(provider.closed, "borrowed provider remains owned by its caller");

        var reopened = registry.open(options, file, ApprovalSettings.none());
        assertEquals(created.sessionId(), reopened.sessionId());
        assertTrue(registry.beginShutdownIfIdle());
        assertEquals(ErrorCode.SESSION_CLOSED, assertThrows(ApiException.class,
                () -> reopened.start(new RunCommand("command-2", "run-2",
                        RunKind.PROMPT, "after stop", null))).error().code());
        registry.close(reopened.sessionId());
        assertFalse(provider.closed);
    }

    private static final class BorrowedProvider implements ModelProvider, AutoCloseable {
        private final List<Model> models = List.of(new Model("test", "scripted", "one", "One"));
        private boolean closed;

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
            return MODEL.equals(ref);
        }

        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            var reply = new Message.Assistant(List.of(new Content.Text("ok")),
                    StopReason.STOP, null, Usage.zero(), Instant.EPOCH, MODEL);
            var stream = new AssistantMessageStream();
            stream.push(new AssistantMessageEvent.Start(reply));
            stream.push(new AssistantMessageEvent.Done(StopReason.STOP, reply));
            return stream;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
