package site.pplee.jcode.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.codingagent.CodingAgentConfig;
import site.pplee.jcode.codingagent.InputDeliveryMode;
import site.pplee.jcode.protocol.ErrorCode;
import site.pplee.jcode.protocol.RunCommand;
import site.pplee.jcode.protocol.RunKind;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SessionRegistryTest {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "model");

    @TempDir
    Path directory;

    @Test
    void oneFileHasOneOwnerAndCanBeReopenedAfterIdleClose() throws Exception {
        var registry = new SessionRegistry();
        var config = config(new ImmediateClient(), InputDeliveryMode.RUN_SCOPED);
        var created = registry.create(config, directory.resolve("sessions"));
        String id = created.sessionId();
        Path file = created.sessionFile().orElseThrow();
        assertSame(created, registry.find(id).orElseThrow());
        assertSame(created, registry.open(config, file));
        var first = CompletableFuture.supplyAsync(() -> open(registry, config, file));
        var second = CompletableFuture.supplyAsync(() -> open(registry, config, file));
        assertSame(created, first.orTimeout(5, TimeUnit.SECONDS).join());
        assertSame(created, second.orTimeout(5, TimeUnit.SECONDS).join());

        registry.close(id);
        assertTrue(registry.find(id).isEmpty());
        var coldOpenGate = new CountDownLatch(1);
        var coldOpenOne = CompletableFuture.supplyAsync(() -> {
            await(coldOpenGate);
            return open(registry, config, file);
        });
        var coldOpenTwo = CompletableFuture.supplyAsync(() -> {
            await(coldOpenGate);
            return open(registry, config, file);
        });
        coldOpenGate.countDown();
        var reopened = coldOpenOne.orTimeout(5, TimeUnit.SECONDS).join();
        assertSame(reopened, coldOpenTwo.orTimeout(5, TimeUnit.SECONDS).join());
        assertEquals(id, reopened.sessionId());
        assertNotSame(created, reopened);
        registry.close(id);
    }

    @Test
    void busySessionCannotCloseAndLegacyModeCannotBeManaged() throws Exception {
        var client = new BlockingClient();
        var registry = new SessionRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.create(
                config(client, InputDeliveryMode.LEGACY_SESSION_QUEUE),
                directory.resolve("sessions")));
        var managed = registry.create(config(client, InputDeliveryMode.RUN_SCOPED),
                directory.resolve("sessions"));
        try {
            managed.start(new RunCommand("command", "run", RunKind.PROMPT, "first", null));
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            assertEquals(ErrorCode.SESSION_BUSY, assertThrows(ApiException.class,
                    () -> registry.close(managed.sessionId())).error().code());
            client.complete();
            managed.settled("run").toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        } finally {
            registry.close(managed.sessionId());
        }
    }

    private CodingAgentConfig config(ModelClient client, InputDeliveryMode mode) {
        return new CodingAgentConfig(directory, MODEL, client, new ObjectMapper(),
                null, null, null, null, null, null, null, null,
                null, null, null, Map.of(), null, mode);
    }

    private static ManagedSession open(SessionRegistry registry, CodingAgentConfig config, Path file) {
        try {
            return registry.open(config, file);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static Message.Assistant assistant() {
        return new Message.Assistant(List.of(new Content.Text("done")), StopReason.STOP,
                null, Usage.zero(), Instant.EPOCH, MODEL);
    }

    private static final class ImmediateClient implements ModelClient {
        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            var stream = new AssistantMessageStream();
            stream.push(new AssistantMessageEvent.Start(assistant()));
            stream.push(new AssistantMessageEvent.Done(StopReason.STOP, assistant()));
            return stream;
        }
    }

    private static final class BlockingClient implements ModelClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AssistantMessageStream stream = new AssistantMessageStream();

        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            stream.push(new AssistantMessageEvent.Start(assistant()));
            started.countDown();
            return stream;
        }

        private void complete() {
            stream.push(new AssistantMessageEvent.Done(StopReason.STOP, assistant()));
        }
    }
}
