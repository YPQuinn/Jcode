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
import site.pplee.jcode.codingagent.event.CodingAgentEvent;
import site.pplee.jcode.codingagent.event.CodingAgentEventSink;
import site.pplee.jcode.protocol.ErrorCode;
import site.pplee.jcode.protocol.InputCommand;
import site.pplee.jcode.protocol.InputMode;
import site.pplee.jcode.protocol.InputStatus;
import site.pplee.jcode.protocol.RunCommand;
import site.pplee.jcode.protocol.RunKind;
import site.pplee.jcode.protocol.RunStatus;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ManagedSessionTest {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "model");

    @TempDir
    Path directory;

    @Test
    void concurrentRunAndInputRetriesKeepOneIdentityAndOneExecution() throws Exception {
        var client = new FirstBlockedClient();
        var registry = new SessionRegistry();
        var session = registry.create(config(client, CodingAgentEventSink.noop()),
                directory.resolve("sessions"));
        try {
            var run = new RunCommand("run-command", "run-a", RunKind.PROMPT, "first", null);
            var gate = new CountDownLatch(1);
            var first = CompletableFuture.supplyAsync(() -> {
                await(gate);
                return session.start(run);
            });
            var duplicate = CompletableFuture.supplyAsync(() -> {
                await(gate);
                return session.start(run);
            });
            gate.countDown();
            assertEquals("run-a", first.orTimeout(5, TimeUnit.SECONDS).join().runId());
            assertEquals("run-a", duplicate.orTimeout(5, TimeUnit.SECONDS).join().runId());
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            assertEquals(1, client.requests.size());
            assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, assertThrows(ApiException.class,
                    () -> session.start(new RunCommand(
                            "run-command", "run-a", RunKind.PROMPT, "changed", null)))
                    .error().code());

            var input = new InputCommand("input-command", "input-a", "run-a",
                    InputMode.FOLLOW_UP, "later");
            var inputGate = new CountDownLatch(1);
            var inputOne = CompletableFuture.supplyAsync(() -> {
                await(inputGate);
                return session.submit(input);
            });
            var inputTwo = CompletableFuture.supplyAsync(() -> {
                await(inputGate);
                return session.submit(input);
            });
            inputGate.countDown();
            assertEquals("input-a", inputOne.orTimeout(5, TimeUnit.SECONDS).join().inputId());
            assertEquals("input-a", inputTwo.orTimeout(5, TimeUnit.SECONDS).join().inputId());
            assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, assertThrows(ApiException.class,
                    () -> session.submit(new InputCommand("input-command", "input-a",
                            "run-a", InputMode.FOLLOW_UP, "changed")))
                    .error().code());

            client.complete(StopReason.STOP);
            var completed = session.settled("run-a").toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(RunStatus.COMPLETED, completed.status());
            assertEquals("done", completed.text());
            assertEquals(2, client.requests.size());
            assertEquals(InputStatus.APPLIED_TO_CONTEXT, session.input("input-a").orElseThrow().status());
            assertNotNull(session.input("input-a").orElseThrow().entryId());
            assertEquals(completed, session.start(run));
            assertEquals(InputStatus.APPLIED_TO_CONTEXT, session.submit(input).status());
            assertEquals(2, client.requests.size());
        } finally {
            registry.close(session.sessionId());
        }
    }

    @Test
    void modelStopsAndStartupFailureBecomeQueryableTerminalRuns() throws Exception {
        var client = new FixedClient(
                assistant(StopReason.STOP),
                assistant(StopReason.ERROR),
                assistant(StopReason.ABORTED),
                assistant(StopReason.STOP));
        var registry = new SessionRegistry();
        var session = registry.create(config(client, CodingAgentEventSink.noop()),
                directory.resolve("sessions"));
        try {
            var rejectedContinue = new RunCommand("command-0", "run-0", RunKind.CONTINUE, null, null);
            session.start(rejectedContinue);
            assertEquals(RunStatus.FAILED, settled(session, "run-0").status());
            assertNull(settled(session, "run-0").stopReason());

            session.start(new RunCommand("command-1", "run-1", RunKind.PROMPT, "one", null));
            assertEquals(RunStatus.COMPLETED, settled(session, "run-1").status());
            assertEquals("STOP", settled(session, "run-1").stopReason());

            session.start(new RunCommand("command-2", "run-2", RunKind.PROMPT, "two", null));
            assertEquals(RunStatus.FAILED, settled(session, "run-2").status());
            assertEquals("ERROR", settled(session, "run-2").stopReason());

            session.start(new RunCommand("command-3", "run-3", RunKind.PROMPT, "three", null));
            assertEquals(RunStatus.CANCELLED, settled(session, "run-3").status());
            assertEquals("ABORTED", settled(session, "run-3").stopReason());

            session.start(new RunCommand("command-4", "run-4", RunKind.PROMPT, "four", null));
            assertEquals(RunStatus.COMPLETED, settled(session, "run-4").status());
            assertEquals(4, client.requests.size());
        } finally {
            registry.close(session.sessionId());
        }
    }

    @Test
    void acceptedCancellationCanFinishNormallyAndOldCancelCannotTouchNewRun() throws Exception {
        var completionReached = new CountDownLatch(1);
        var completionGate = new CompletableFuture<Void>();
        var finishedCount = new AtomicInteger();
        var sink = (CodingAgentEventSink) event -> {
            if (event instanceof CodingAgentEvent.RunCompleted
                    && finishedCount.incrementAndGet() == 1) {
                completionReached.countDown();
                return completionGate;
            }
            return CompletableFuture.completedStage(null);
        };
        var client = new SecondBlockedClient();
        var registry = new SessionRegistry();
        var session = registry.create(config(client, sink), directory.resolve("sessions"));
        try {
            session.start(new RunCommand("command-a", "run-a", RunKind.PROMPT, "first", null));
            assertTrue(completionReached.await(5, TimeUnit.SECONDS));
            assertEquals(RunStatus.CANCELLING, session.cancel("run-a").status());
            completionGate.complete(null);
            var first = settled(session, "run-a");
            assertEquals(RunStatus.COMPLETED, first.status());
            assertTrue(first.cancelRequested());

            session.start(new RunCommand("command-b", "run-b", RunKind.PROMPT, "second", null));
            assertTrue(client.secondStarted.await(5, TimeUnit.SECONDS));
            assertEquals(first, session.cancel("run-a"));
            assertEquals(RunStatus.RUNNING, session.view("run-b").orElseThrow().status());
            client.completeSecond();
            assertEquals(RunStatus.COMPLETED, settled(session, "run-b").status());
            assertFalse(settled(session, "run-b").cancelRequested());
        } finally {
            completionGate.complete(null);
            registry.close(session.sessionId());
        }
    }

    @Test
    void oldExpectedLeafOnlyConflictsForANewCommand() throws Exception {
        var client = new FixedClient(assistant(StopReason.STOP), assistant(StopReason.STOP));
        var registry = new SessionRegistry();
        var session = registry.create(config(client, CodingAgentEventSink.noop()),
                directory.resolve("sessions"));
        try {
            var first = new RunCommand("command-a", "run-a", RunKind.PROMPT, "first", null);
            session.start(first);
            settled(session, "run-a");
            assertEquals(RunStatus.COMPLETED, session.start(first).status());
            assertEquals(ErrorCode.STATE_CONFLICT, assertThrows(ApiException.class,
                    () -> session.start(new RunCommand("command-b", "run-b", RunKind.PROMPT,
                            "second", "stale-leaf"))).error().code());
            assertEquals(1, client.requests.size());
            session.start(new RunCommand("command-c", "run-c", RunKind.PROMPT,
                    "second", session.currentLeafId().orElseThrow()));
            assertEquals(RunStatus.COMPLETED, settled(session, "run-c").status());
        } finally {
            registry.close(session.sessionId());
        }
    }

    @Test
    void infrastructureFailureIsQueryableAndReleasesRunAdmission() throws Exception {
        var failures = new AtomicInteger();
        var sink = (CodingAgentEventSink) event -> {
            if (event instanceof CodingAgentEvent.RuntimeEvent && failures.getAndIncrement() == 0) {
                return CompletableFuture.failedStage(new IllegalStateException("injected sink failure"));
            }
            return CompletableFuture.completedStage(null);
        };
        var client = new FixedClient(assistant(StopReason.STOP));
        var registry = new SessionRegistry();
        var session = registry.create(config(client, sink), directory.resolve("sessions"));
        try {
            session.start(new RunCommand("command-a", "run-a", RunKind.PROMPT, "first", null));
            var failed = settled(session, "run-a");
            assertEquals(RunStatus.FAILED, failed.status());
            assertNull(failed.stopReason());
            assertEquals(0, client.requests.size());

            session.start(new RunCommand("command-b", "run-b", RunKind.PROMPT, "second", null));
            assertEquals(RunStatus.COMPLETED, settled(session, "run-b").status());
            assertEquals(1, client.requests.size());
        } finally {
            registry.close(session.sessionId());
        }
    }

    @Test
    void activeCommandReceiptsCannotBeEvictedAtCapacity() throws Exception {
        var client = new FirstBlockedClient();
        var registry = new SessionRegistry();
        var session = registry.create(config(client, CodingAgentEventSink.noop()),
                directory.resolve("sessions"));
        try {
            session.start(new RunCommand("start", "run-a", RunKind.PROMPT, "first", null));
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            for (int index = 0; index < ManagedSession.MAX_RETAINED_COMMANDS - 1; index++) {
                session.submit(new InputCommand("input-command-" + index, "input-" + index,
                        "run-a", InputMode.FOLLOW_UP, "later " + index));
            }
            assertEquals(ErrorCode.CAPACITY_EXCEEDED, assertThrows(ApiException.class,
                    () -> session.submit(new InputCommand("overflow", "too-many", "run-a",
                            InputMode.FOLLOW_UP, "not admitted"))).error().code());
            assertTrue(session.input("too-many").isEmpty());
            session.cancel("run-a");
            assertEquals(RunStatus.CANCELLED, settled(session, "run-a").status());

            session.start(new RunCommand("next", "run-b", RunKind.PROMPT, "second", null));
            assertEquals(RunStatus.COMPLETED, settled(session, "run-b").status());
        } finally {
            registry.close(session.sessionId());
        }
    }

    private CodingAgentConfig config(ModelClient client, CodingAgentEventSink sink) {
        return new CodingAgentConfig(directory, MODEL, client, new ObjectMapper(),
                null, null, null, null, null, null, sink, null,
                null, null, null, Map.of(), null, InputDeliveryMode.RUN_SCOPED);
    }

    private static site.pplee.jcode.protocol.RunView settled(ManagedSession session, String runId) {
        return session.settled(runId).toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static Message.Assistant assistant(StopReason reason) {
        return new Message.Assistant(List.of(new Content.Text("done")), reason,
                reason.isTerminalFailure() ? reason.name() : null,
                Usage.zero(), Instant.EPOCH, MODEL);
    }

    private static AssistantMessageStream completed(Message.Assistant response) {
        var stream = new AssistantMessageStream();
        stream.push(new AssistantMessageEvent.Start(response));
        if (response.stopReason().isTerminalFailure()) {
            stream.push(new AssistantMessageEvent.Error(response.stopReason(), response));
        } else {
            stream.push(new AssistantMessageEvent.Done(response.stopReason(), response));
        }
        return stream;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for concurrent start");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class FixedClient implements ModelClient {
        private final java.util.Queue<Message.Assistant> responses = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();

        private FixedClient(Message.Assistant... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            requests.add(request);
            return completed(responses.remove());
        }
    }

    private static final class FirstBlockedClient implements ModelClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AssistantMessageStream first = new AssistantMessageStream();
        private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            requests.add(request);
            if (requests.size() > 1) {
                return completed(assistant(StopReason.STOP));
            }
            first.push(new AssistantMessageEvent.Start(assistant(StopReason.STOP)));
            cancellation.onCancellation(() -> first.push(new AssistantMessageEvent.Error(
                    StopReason.ABORTED, assistant(StopReason.ABORTED))));
            started.countDown();
            return first;
        }

        private void complete(StopReason reason) {
            var result = assistant(reason);
            first.push(new AssistantMessageEvent.Done(reason, result));
        }
    }

    private static final class SecondBlockedClient implements ModelClient {
        private final CountDownLatch secondStarted = new CountDownLatch(1);
        private final AssistantMessageStream second = new AssistantMessageStream();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            if (calls.incrementAndGet() == 1) {
                return completed(assistant(StopReason.STOP));
            }
            second.push(new AssistantMessageEvent.Start(assistant(StopReason.STOP)));
            cancellation.onCancellation(() -> second.push(new AssistantMessageEvent.Error(
                    StopReason.ABORTED, assistant(StopReason.ABORTED))));
            secondStarted.countDown();
            return second;
        }

        private void completeSecond() {
            second.push(new AssistantMessageEvent.Done(StopReason.STOP, assistant(StopReason.STOP)));
        }
    }
}
