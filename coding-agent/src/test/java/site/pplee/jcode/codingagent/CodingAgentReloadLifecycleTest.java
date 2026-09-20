package site.pplee.jcode.codingagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.codingagent.context.ProjectContextConfig;
import site.pplee.jcode.codingagent.context.ProjectContextFailureMode;
import site.pplee.jcode.codingagent.context.ProjectContextLoadException;
import site.pplee.jcode.codingagent.context.ProjectContextLoader;
import site.pplee.jcode.codingagent.support.ReloadGate;
import site.pplee.jcode.codingagent.support.ScriptedModelClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CodingAgentReloadLifecycleTest {
    private static final ModelRef MODEL = new ModelRef("fake", "test", "fake-model");

    @TempDir
    Path directory;

    @Test
    void successfulReloadCallbackCanStartPrompt() throws Exception {
        Files.writeString(directory.resolve("AGENTS.md"), "initial rules");
        var client = client();
        var gate = new ReloadGate();
        try (var session = session(client, gate); gate) {
            Files.writeString(directory.resolve("AGENTS.md"), "updated rules");
            var reload = session.reloadProjectContext();
            gate.awaitEntered();
            var nextRun = reload.thenCompose(snapshot -> {
                assertFalse(session.isReloading());
                assertEquals(2, snapshot.revision());
                return session.prompt("next");
            });

            gate.close();
            nextRun.toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertTrue(client.requests().getFirst().systemPrompt().contains("updated rules"));
        }
    }

    @Test
    void failedReloadCallbackCanRecoverWithPrompt() throws Exception {
        Files.writeString(directory.resolve("AGENTS.md"), "stable rules");
        var client = client();
        var gate = new ReloadGate();
        try (var session = session(client, gate); gate) {
            Files.write(directory.resolve("AGENTS.md"), new byte[]{(byte) 0xff});
            var reload = session.reloadProjectContext();
            gate.awaitEntered();
            var nextRun = reload.handle((snapshot, failure) -> {
                assertInstanceOf(ProjectContextLoadException.class, failure.getCause());
                assertFalse(session.isReloading());
                assertEquals(1, session.projectContext().revision());
                return session.prompt("recover");
            }).thenCompose(stage -> stage);

            gate.close();
            nextRun.toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertTrue(client.requests().getFirst().systemPrompt().contains("stable rules"));
        }
    }

    @Test
    void cancelledReloadCallbackCanStartAnotherReload() throws Exception {
        Files.writeString(directory.resolve("AGENTS.md"), "stable rules");
        var gate = new ReloadGate();
        try (var session = session(client(), gate); gate) {
            var reload = session.reloadProjectContext();
            gate.awaitEntered();
            var retried = reload.handle((snapshot, failure) -> {
                assertInstanceOf(CancellationException.class, failure.getCause());
                assertFalse(session.isReloading());
                return session.reloadProjectContext();
            }).thenCompose(stage -> stage);

            session.abort();
            gate.close();

            assertEquals(2, retried.toCompletableFuture().get(5, TimeUnit.SECONDS).revision());
            assertFalse(session.isReloading());
        }
    }

    @Test
    void closeTimeoutDoesNotSettleOrReleaseAnUnfinishedReload() throws Exception {
        Files.writeString(directory.resolve("AGENTS.md"), "stable rules");
        var gate = new ReloadGate();
        try (var session = session(client(), gate); gate) {
            var reload = session.reloadProjectContext().toCompletableFuture();
            gate.awaitEntered();
            try {
                session.close();

                assertFalse(reload.isDone(), "close must not pretend that blocked file I/O has exited");
                assertTrue(session.isReloading());
                assertEquals(1, session.projectContext().revision());
                assertThrows(IllegalStateException.class, session::reloadProjectContext);
            } finally {
                gate.close();
                reload.handle((snapshot, failure) -> null).get(5, TimeUnit.SECONDS);
            }

            var failure = assertThrows(CompletionException.class, reload::join);
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertFalse(session.isReloading());
            assertEquals(1, session.projectContext().revision());
        }
    }

    @Test
    void successfulReloadCallbackCanCloseSessionWithoutUndoingCommit() throws Exception {
        Files.writeString(directory.resolve("AGENTS.md"), "rules");
        var gate = new ReloadGate();
        try (var session = session(client(), gate); gate) {
            var reload = session.reloadProjectContext();
            gate.awaitEntered();
            var closed = reload.thenApply(snapshot -> {
                session.close();
                return snapshot;
            });

            gate.close();

            assertEquals(2, closed.toCompletableFuture().get(5, TimeUnit.SECONDS).revision());
            assertEquals(2, session.projectContext().revision());
            assertFalse(session.isReloading());
            assertThrows(IllegalStateException.class, () -> session.prompt("closed"));
        }
    }

    @Test
    void callbackCanCloseWhileTheNextReloadWorkerIsStillActive() throws Exception {
        Files.writeString(directory.resolve("AGENTS.md"), "rules");
        var first = new ReloadGate();
        var next = new ReloadGate();
        try (var session = new CodingAgentSession(config(client()), (cwd, options, revision, cancellation) -> {
            if (revision == 2) {
                first.awaitRelease();
            } else if (revision == 3) {
                next.awaitRelease();
            }
            return ProjectContextLoader.load(cwd, options, revision, cancellation);
        }); first; next) {
            var reload = session.reloadProjectContext();
            first.awaitEntered();
            var closed = reload.thenApply(snapshot -> {
                var nextReload = session.reloadProjectContext();
                try {
                    next.awaitEntered();
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
                session.close();
                return nextReload;
            });
            first.close();

            var nextReload = closed.toCompletableFuture().get(5, TimeUnit.SECONDS).toCompletableFuture();
            assertFalse(nextReload.isDone());
            assertTrue(session.isReloading());
            assertEquals(2, session.projectContext().revision());
            next.close();
            nextReload.handle((snapshot, failure) -> null).get(5, TimeUnit.SECONDS);

            var failure = assertThrows(CompletionException.class, nextReload::join);
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertFalse(session.isReloading());
        }
    }

    @Test
    void closeNormalizesInterruptedLoaderFailureAfterWorkerCleanup() throws Exception {
        Files.writeString(directory.resolve("AGENTS.md"), "rules");
        var entered = new CountDownLatch(1);
        var blocked = new CountDownLatch(1);
        try (var session = new CodingAgentSession(config(client()), (cwd, options, revision, cancellation) -> {
            if (revision > 1) {
                entered.countDown();
                try {
                    blocked.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("loader interrupted", failure);
                }
            }
            return ProjectContextLoader.load(cwd, options, revision, cancellation);
        })) {
            var reload = session.reloadProjectContext().toCompletableFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            session.close();
            reload.handle((snapshot, failure) -> null).get(5, TimeUnit.SECONDS);

            var failure = assertThrows(CompletionException.class, reload::join);
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertFalse(session.isReloading());
            assertEquals(1, session.projectContext().revision());
        } finally {
            blocked.countDown();
        }
    }

    private CodingAgentConfig config(ScriptedModelClient client) {
        return new CodingAgentConfig(directory, MODEL, client, new ObjectMapper(),
                null, null, null, null, null, null, null, null, null,
                new ProjectContextConfig(true, null, directory, ProjectContextFailureMode.FAIL));
    }

    private CodingAgentSession session(ScriptedModelClient client, ReloadGate gate) {
        return new CodingAgentSession(config(client), (cwd, options, revision, cancellation) -> {
            if (revision > 1) {
                gate.awaitRelease();
            }
            return ProjectContextLoader.load(cwd, options, revision, cancellation);
        });
    }

    private ScriptedModelClient client() {
        return new ScriptedModelClient(request -> new Message.Assistant(
                List.of(new Content.Text("done")), StopReason.STOP, null, Usage.zero(), Instant.EPOCH, MODEL));
    }
}
