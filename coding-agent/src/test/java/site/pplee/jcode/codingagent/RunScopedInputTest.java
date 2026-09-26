package site.pplee.jcode.codingagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.ModelFailureKind;
import site.pplee.jcode.ai.message.ResponseMetadata;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.codingagent.event.CodingAgentEvent;
import site.pplee.jcode.codingagent.event.CodingAgentEventSink;
import site.pplee.jcode.codingagent.compaction.SummaryCause;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;
import site.pplee.jcode.codingagent.settings.CompactionSettings;
import site.pplee.jcode.codingagent.model.ModelProfile;
import site.pplee.jcode.codingagent.support.ScriptedModelClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RunScopedInputTest {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "model");

    @TempDir
    Path directory;

    @Test
    void steeringAndFollowUpUseOneRunAndConfirmActualHistoryEntries() throws Exception {
        var client = new ControlledClient();
        try (var session = strictSession(client, CodingAgentEventSink.noop())) {
            assertThrows(IllegalStateException.class, () -> session.prompt("legacy"));
            assertThrows(IllegalStateException.class, () -> session.steer("legacy"));
            var run = session.prompt("run-a", "start");
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            var steer = new InputRequest("steer-1", "run-a", InputMode.STEER, "steer now");
            var follow = new InputRequest("follow-1", "run-a", InputMode.FOLLOW_UP, "follow then");
            assertEquals(InputStatus.PENDING, session.submitInput(steer).status());
            assertEquals(InputStatus.PENDING, session.submitInput(follow).status());
            assertEquals(2, session.inputs("run-a").size());
            assertEquals(InputStatus.PENDING, session.submitInput(steer).status());
            assertThrows(IllegalArgumentException.class, () -> session.submitInput(
                    new InputRequest("steer-1", "run-a", InputMode.STEER, "different")));
            client.complete(StopReason.STOP);
            run.toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();

            assertEquals(3, client.requests.size());
            assertEquals("steer now", lastUserText(client.requests.get(1)));
            assertEquals("follow then", lastUserText(client.requests.get(2)));
            for (var request : List.of(steer, follow)) {
                var record = session.input(request.inputId()).orElseThrow();
                assertEquals(InputStatus.APPLIED_TO_CONTEXT, record.status());
                var entry = assertInstanceOf(SessionMessageEntry.class,
                        session.history().entry(record.entryId()).orElseThrow());
                var stored = assertInstanceOf(Message.User.class, entry.message().message());
                assertEquals(request.text(), text(stored));
                assertEquals(1, session.history().entries().stream()
                        .filter(SessionMessageEntry.class::isInstance)
                        .map(SessionMessageEntry.class::cast)
                        .map(item -> item.message().message())
                        .filter(Message.User.class::isInstance)
                        .map(Message.User.class::cast)
                        .filter(user -> text(user).equals(request.text())).count());
            }
        }
    }

    @Test
    void completionBackpressureClosesInputAndLateCancelDoesNotChangeResult() throws Exception {
        var reached = new CountDownLatch(1);
        var gate = new CompletableFuture<Void>();
        var completed = new AtomicInteger();
        var client = new ScriptedModelClient(request -> assistant(StopReason.STOP),
                request -> assistant(StopReason.STOP));
        var sink = (CodingAgentEventSink) event -> {
            if (event instanceof CodingAgentEvent.RunCompleted && completed.incrementAndGet() == 1) {
                reached.countDown();
                return gate;
            }
            return CompletableFuture.completedStage(null);
        };
        try (var session = strictSession(client, sink)) {
            var first = session.prompt("run-a", "first");
            assertTrue(reached.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> session.submitInput(
                    new InputRequest("late", "run-a", InputMode.FOLLOW_UP, "late text")));
            session.abort("run-a");
            gate.complete(null);
            assertEquals(StopReason.STOP, first.toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS).join().finalMessage().stopReason());
            session.prompt("run-b", "second").toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(2, client.requests().size());
            assertTrue(session.input("late").isEmpty());
        }
    }

    @Test
    void cancelledRunSettlesPendingInputAndNeverInjectsItOnAnotherBranch() throws Exception {
        var client = new ControlledClient();
        try (var session = strictSession(client, CodingAgentEventSink.noop())) {
            var first = session.prompt("run-a", "first");
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            session.submitInput(new InputRequest("old", "run-a", InputMode.FOLLOW_UP, "old input"));
            session.abort("wrong-run");
            assertEquals(InputStatus.PENDING, session.input("old").orElseThrow().status());
            session.abort("run-a");
            assertTrue(first.toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join().aborted());
            assertEquals(InputStatus.NOT_APPLIED, session.input("old").orElseThrow().status());
            var firstEntry = session.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance).findFirst().orElseThrow().id();
            session.branch(firstEntry);
            session.prompt("run-b", "second").toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(2, client.requests.size());
            assertEquals("second", lastUserText(client.requests.get(1)));
            session.resetLeaf();
            assertEquals(InputStatus.NOT_APPLIED, session.input("old").orElseThrow().status());
        }
    }

    @Test
    void terminalModelErrorSettlesPendingInput() throws Exception {
        var client = new ControlledClient();
        try (var session = strictSession(client, CodingAgentEventSink.noop())) {
            var first = session.prompt("run-a", "first");
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            session.submitInput(new InputRequest("old", "run-a", InputMode.FOLLOW_UP, "old input"));
            client.complete(StopReason.ERROR);
            assertEquals(StopReason.ERROR, first.toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS).join().finalMessage().stopReason());
            assertEquals(InputStatus.NOT_APPLIED, session.input("old").orElseThrow().status());
            session.prompt("run-b", "second").toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(2, client.requests.size());
        }
    }

    @Test
    void cancellationAfterClaimDoesNotReportInputApplied() throws Exception {
        var turnReached = new CountDownLatch(1);
        var turnGate = new CompletableFuture<Void>();
        var turns = new AtomicInteger();
        var sink = (CodingAgentEventSink) event -> {
            if (event instanceof CodingAgentEvent.RuntimeEvent runtime
                    && runtime.event() instanceof AgentEvent.TurnStarted
                    && turns.incrementAndGet() == 2) {
                turnReached.countDown();
                return turnGate;
            }
            return CompletableFuture.completedStage(null);
        };
        var client = new ControlledClient();
        try (var session = strictSession(client, sink)) {
            var run = session.prompt("run-a", "first");
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            session.submitInput(new InputRequest("steer", "run-a", InputMode.STEER, "do this"));
            client.complete(StopReason.STOP);
            assertTrue(turnReached.await(5, TimeUnit.SECONDS));
            session.abort("run-a");
            turnGate.complete(null);
            run.toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(InputStatus.NOT_APPLIED, session.input("steer").orElseThrow().status());
            assertEquals(1, client.requests.size());
        }
    }

    @Test
    void observerFailureAfterHistoryCommitRetainsAppliedIdentity() throws Exception {
        var client = new ControlledClient();
        var sink = (CodingAgentEventSink) event -> {
            if (event instanceof CodingAgentEvent.RuntimeEvent runtime
                    && runtime.event() instanceof AgentEvent.MessageCompleted completed
                    && "follow".equals(completed.inputId())) {
                return CompletableFuture.failedStage(new IllegalStateException("observer failed"));
            }
            return CompletableFuture.completedStage(null);
        };
        try (var session = strictSession(client, sink)) {
            var run = session.prompt("run-a", "first");
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            session.submitInput(new InputRequest("follow", "run-a", InputMode.FOLLOW_UP, "later"));
            client.complete(StopReason.STOP);
            assertThrows(CompletionException.class, () -> run.toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS).join());
            var record = session.input("follow").orElseThrow();
            assertEquals(InputStatus.APPLIED_TO_CONTEXT, record.status());
            assertTrue(session.history().entry(record.entryId()).isPresent());
        }
    }

    @Test
    void admissionRacingWithRunEndIsEitherRejectedOrSettled() throws Exception {
        var client = new ControlledClient();
        try (var session = strictSession(client, CodingAgentEventSink.noop())) {
            var run = session.prompt("run-a", "first");
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            var start = new CountDownLatch(1);
            var request = new InputRequest("racing", "run-a", InputMode.FOLLOW_UP, "possible follow-up");
            var admission = CompletableFuture.supplyAsync(() -> {
                await(start);
                try {
                    return session.submitInput(request);
                } catch (IllegalStateException closed) {
                    return null;
                }
            });
            var completion = CompletableFuture.runAsync(() -> {
                await(start);
                client.complete(StopReason.STOP);
            });
            start.countDown();
            completion.orTimeout(5, TimeUnit.SECONDS).join();
            var admitted = admission.orTimeout(5, TimeUnit.SECONDS).join();
            run.toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            if (admitted == null) {
                assertTrue(session.input("racing").isEmpty());
            } else {
                var settled = session.input("racing").orElseThrow();
                assertTrue(settled.status() == InputStatus.APPLIED_TO_CONTEXT
                        || settled.status() == InputStatus.NOT_APPLIED);
            }
        }
    }

    @Test
    void overflowRecoveryKeepsProductRunAndAppliesFollowUpOnce() {
        var overflow = new Message.Assistant(
                List.of(), StopReason.ERROR, "context exceeded", Usage.zero(), Instant.EPOCH,
                MODEL, ResponseMetadata.of(null, null, null, ModelFailureKind.CONTEXT_OVERFLOW));
        var client = new ScriptedModelClient(
                request -> assistant(StopReason.STOP),
                request -> overflow,
                request -> assistant(StopReason.STOP),
                request -> assistant(StopReason.STOP),
                request -> {
                    assertEquals("after recovery", lastUserText(request));
                    return assistant(StopReason.STOP);
                });
        var sessionRef = new AtomicReference<CodingAgentSession>();
        var sink = (CodingAgentEventSink) event -> {
            if (event instanceof CodingAgentEvent.RuntimeEvent runtime
                    && runtime.event() instanceof AgentEvent.MessageCompleted completed
                    && completed.message() instanceof site.pplee.jcode.agentcore.message.StandardAgentMessage standard
                    && standard.message() instanceof Message.Assistant assistant
                    && assistant.metadata().failureKind()
                            .filter(kind -> kind == ModelFailureKind.CONTEXT_OVERFLOW).isPresent()) {
                sessionRef.get().submitInput(new InputRequest(
                        "recovery-follow", "run-b", InputMode.FOLLOW_UP, "after recovery"));
            }
            return CompletableFuture.completedStage(null);
        };
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, sink, null, null, null,
                new CompactionSettings(true, 1_000, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(10_000), OptionalInt.of(2_000))),
                null, InputDeliveryMode.RUN_SCOPED);
        try (var session = new CodingAgentSession(config)) {
            sessionRef.set(session);
            session.prompt("run-a", "establish context ".repeat(80)).toCompletableFuture().join();
            session.prompt("run-b", "overflow now").toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(5, client.requests().size());
            var record = session.input("recovery-follow").orElseThrow();
            assertEquals("run-b", record.targetRunId());
            assertEquals(InputStatus.APPLIED_TO_CONTEXT, record.status());
            assertTrue(session.history().entry(record.entryId()).isPresent());
            assertEquals(1, session.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .map(item -> item.message().message())
                    .filter(Message.User.class::isInstance)
                    .map(Message.User.class::cast)
                    .filter(user -> text(user).equals("after recovery")).count());
        }
    }

    @Test
    void cancellationMarkedBeforeDeliveryPreventsStartingOverflowRecovery() throws Exception {
        var summaryReached = new CountDownLatch(1);
        var summaryGate = new CompletableFuture<Void>();
        var overflow = new Message.Assistant(
                List.of(), StopReason.ERROR, "context exceeded", Usage.zero(), Instant.EPOCH,
                MODEL, ResponseMetadata.of(null, null, null, ModelFailureKind.CONTEXT_OVERFLOW));
        var client = new ScriptedModelClient(
                request -> assistant(StopReason.STOP),
                request -> overflow,
                request -> assistant(StopReason.STOP),
                request -> {
                    throw new AssertionError("recovery must not start after cancellation was admitted");
                });
        var sink = (CodingAgentEventSink) event -> {
            if (event instanceof CodingAgentEvent.SummaryCompleted completed
                    && completed.cause() == SummaryCause.OVERFLOW) {
                summaryReached.countDown();
                return summaryGate;
            }
            return CompletableFuture.completedStage(null);
        };
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, sink, null, null, null,
                new CompactionSettings(true, 1_000, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(10_000), OptionalInt.of(2_000))),
                null, InputDeliveryMode.RUN_SCOPED);
        try (var session = new CodingAgentSession(config)) {
            session.prompt("run-a", "establish context ".repeat(80)).toCompletableFuture().join();
            var run = session.prompt("run-b", "overflow now");
            assertTrue(summaryReached.await(5, TimeUnit.SECONDS));

            // Hold signal delivery after the real cancellation admission path captured the old stage.
            Runnable deliverCancellation = session.requestRunCancellation("run-b");
            assertThrows(IllegalStateException.class, () -> session.submitInput(
                    new InputRequest("late", "run-b", InputMode.FOLLOW_UP, "too late")));
            summaryGate.complete(null);
            assertThrows(CompletionException.class, () -> run.toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS).join());
            deliverCancellation.run();
            assertEquals(3, client.requests().size());
            assertFalse(session.isRunning());
        }
    }

    @Test
    void fileBackedStrictInputKeepsConfirmedEntryAfterReopen() throws Exception {
        var client = new ControlledClient();
        var config = strictConfig(client, CodingAgentEventSink.noop());
        Path file;
        String appliedEntryId;
        try (var session = CodingAgentSession.create(config, directory.resolve("sessions"))) {
            file = session.sessionFile().orElseThrow();
            var run = session.prompt("run-a", "first");
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            session.submitInput(new InputRequest("follow", "run-a", InputMode.FOLLOW_UP, "persist me"));
            client.complete(StopReason.STOP);
            run.toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            var record = session.input("follow").orElseThrow();
            assertEquals(InputStatus.APPLIED_TO_CONTEXT, record.status());
            appliedEntryId = record.entryId();
            assertEquals("persist me", storedUserText(session, appliedEntryId));
        }
        try (var reopened = CodingAgentSession.open(config, file)) {
            assertEquals("persist me", storedUserText(reopened, appliedEntryId));
        }
    }

    @Test
    void failedFileAppendLeavesInputForReconciliationWithoutRedelivery() throws Exception {
        var client = new ControlledClient();
        var config = strictConfig(client, CodingAgentEventSink.noop());
        Path file;
        try (var created = CodingAgentSession.create(config, directory.resolve("sessions"))) {
            file = created.sessionFile().orElseThrow();
        }
        var manager = SessionManager.openFileBacked(file, Clock.systemUTC(),
                () -> java.util.UUID.randomUUID().toString(), channel -> source -> {
                    if (remainingText(source).contains("writer failure input")) {
                        var prefix = source.duplicate();
                        prefix.limit(prefix.position() + Math.min(8, prefix.remaining()));
                        channel.write(prefix);
                        throw new IOException("injected append failure");
                    }
                    return channel.write(source);
                });
        try (var session = new CodingAgentSession(
                config, site.pplee.jcode.codingagent.context.ProjectContextLoader::load, manager)) {
            var run = session.prompt("run-a", "first");
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            session.submitInput(new InputRequest(
                    "failed", "run-a", InputMode.FOLLOW_UP, "writer failure input"));
            client.complete(StopReason.STOP);
            assertThrows(CompletionException.class, () -> run.toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS).join());
            var record = session.input("failed").orElseThrow();
            assertEquals(InputStatus.RECONCILIATION_REQUIRED, record.status());
            assertNull(record.entryId());
            assertTrue(session.inputs("run-a").stream()
                    .noneMatch(input -> input.status() == InputStatus.PENDING));
            assertEquals(1, client.requests.size());
            assertThrows(CompletionException.class, () -> session.prompt("run-b", "second")
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join());
            assertEquals(1, client.requests.size());
        }
    }

    @Test
    void cancellationDuringFileCommitKeepsSuccessfullyWrittenInputApplied() throws Exception {
        var client = new ControlledClient();
        var config = strictConfig(client, CodingAgentEventSink.noop());
        Path file;
        try (var created = CodingAgentSession.create(config, directory.resolve("sessions"))) {
            file = created.sessionFile().orElseThrow();
        }
        var commitReached = new CountDownLatch(1);
        var commitGate = new CountDownLatch(1);
        var manager = SessionManager.openFileBacked(file, Clock.systemUTC(),
                () -> java.util.UUID.randomUUID().toString(), channel -> source -> {
                    if (remainingText(source).contains("commit gate input")) {
                        commitReached.countDown();
                        await(commitGate);
                    }
                    return channel.write(source);
                });
        try (var session = new CodingAgentSession(
                config, site.pplee.jcode.codingagent.context.ProjectContextLoader::load, manager)) {
            var run = session.prompt("run-a", "first");
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            session.submitInput(new InputRequest(
                    "committing", "run-a", InputMode.FOLLOW_UP, "commit gate input"));
            client.complete(StopReason.STOP);
            assertTrue(commitReached.await(5, TimeUnit.SECONDS));
            assertEquals(InputStatus.PENDING, session.input("committing").orElseThrow().status());
            session.abort("run-a");
            commitGate.countDown();
            run.toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            var record = session.input("committing").orElseThrow();
            assertEquals(InputStatus.APPLIED_TO_CONTEXT, record.status());
            assertEquals("commit gate input", storedUserText(session, record.entryId()));
        } finally {
            commitGate.countDown();
        }
    }

    private CodingAgentSession strictSession(ModelClient client, CodingAgentEventSink sink) {
        return new CodingAgentSession(strictConfig(client, sink));
    }

    private CodingAgentConfig strictConfig(ModelClient client, CodingAgentEventSink sink) {
        return new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, sink, null, null, null, null, Map.of(), null,
                InputDeliveryMode.RUN_SCOPED);
    }

    private static String storedUserText(CodingAgentSession session, String entryId) {
        var entry = assertInstanceOf(SessionMessageEntry.class,
                session.history().entry(entryId).orElseThrow());
        return text(assertInstanceOf(Message.User.class, entry.message().message()));
    }

    private static String remainingText(java.nio.ByteBuffer source) {
        return StandardCharsets.UTF_8.decode(source.asReadOnlyBuffer()).toString();
    }

    private static String lastUserText(ModelRequest request) {
        return request.messages().stream().filter(Message.User.class::isInstance)
                .map(Message.User.class::cast).map(RunScopedInputTest::text)
                .reduce((first, second) -> second).orElseThrow();
    }

    private static String text(Message.User user) {
        return assertInstanceOf(Content.Text.class, user.content().getFirst()).text();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to race input admission");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static Message.Assistant assistant(StopReason reason) {
        return new Message.Assistant(List.of(new Content.Text("done")), reason,
                reason == StopReason.ERROR ? "failed" : null,
                Usage.zero(), Instant.EPOCH, MODEL);
    }

    private static final class ControlledClient implements ModelClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AssistantMessageStream first = new AssistantMessageStream();
        private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            requests.add(request);
            if (requests.size() > 1) {
                return completed(assistant(StopReason.STOP));
            }
            cancellation.onCancellation(() -> complete(StopReason.ABORTED));
            first.push(new AssistantMessageEvent.Start(assistant(StopReason.STOP)));
            started.countDown();
            return first;
        }

        void complete(StopReason reason) {
            var response = assistant(reason);
            if (reason.isTerminalFailure()) {
                first.push(new AssistantMessageEvent.Error(reason, response));
            } else {
                first.push(new AssistantMessageEvent.Done(reason, response));
            }
        }

        private static AssistantMessageStream completed(Message.Assistant response) {
            var stream = new AssistantMessageStream();
            stream.push(new AssistantMessageEvent.Start(response));
            stream.push(new AssistantMessageEvent.Done(response.stopReason(), response));
            return stream;
        }
    }
}
