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
import site.pplee.jcode.protocol.ApprovalCommand;
import site.pplee.jcode.protocol.ApprovalDecision;
import site.pplee.jcode.protocol.ApprovalStatus;
import site.pplee.jcode.protocol.ApprovalView;
import site.pplee.jcode.protocol.ErrorCode;
import site.pplee.jcode.protocol.EventType;
import site.pplee.jcode.protocol.InputCommand;
import site.pplee.jcode.protocol.InputMode;
import site.pplee.jcode.protocol.InputStatus;
import site.pplee.jcode.protocol.RunCommand;
import site.pplee.jcode.protocol.RunKind;
import site.pplee.jcode.protocol.RunStatus;
import site.pplee.jcode.protocol.SessionReducer;
import site.pplee.jcode.protocol.SessionSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ManagedSessionA3Test {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "model");

    @TempDir
    Path directory;

    @Test
    void anotherSubscriberRecoversApprovalAndInputWithoutStoppingTheRun() throws Exception {
        Files.writeString(directory.resolve("allowed.txt"), "hello");
        var mapper = new ObjectMapper();
        var client = new ReadThenStopClient(mapper);
        var registry = new SessionRegistry();
        var session = registry.create(config(client, mapper), directory.resolve("sessions"),
                new ApprovalSettings(Set.of("read"), Duration.ofSeconds(30)));
        try {
            var beforeRun = session.snapshot();
            ApprovalView pending;
            try (var firstClient = session.subscribe(beforeRun.cursor())) {
                session.start(new RunCommand("command-1", "run-1", RunKind.PROMPT, "read it", null));
                pending = waitForApproval(firstClient, mapper);
            }
            assertEquals(ApprovalStatus.PENDING, pending.status());
            assertEquals(RunStatus.RUNNING, session.view("run-1").orElseThrow().status());

            session.submit(new InputCommand("input-command", "input-1", "run-1",
                    InputMode.FOLLOW_UP, "then summarize"));
            SessionSnapshot takeover = session.snapshot();
            assertEquals(ApprovalStatus.PENDING, takeover.approvals().getFirst().status());
            assertEquals(InputStatus.PENDING, takeover.inputs().getFirst().status());

            try (var secondClient = session.subscribe(takeover.cursor())) {
                assertEquals(ErrorCode.STATE_CONFLICT, assertThrows(ApiException.class,
                        () -> session.resolve(new ApprovalCommand(pending.approvalId(),
                                pending.toolCallId(), "different-request", ApprovalDecision.ALLOW)))
                        .error().code());
                var decision = new ApprovalCommand(pending.approvalId(), pending.toolCallId(),
                        pending.requestDigest(), ApprovalDecision.ALLOW);
                assertEquals(ApprovalStatus.ALLOWED, session.resolve(decision).status());
                assertEquals(ApprovalStatus.ALLOWED, session.resolve(decision).status());
                assertEquals(ErrorCode.APPROVAL_CLOSED, assertThrows(ApiException.class,
                        () -> session.resolve(new ApprovalCommand(pending.approvalId(),
                                pending.toolCallId(), pending.requestDigest(),
                                ApprovalDecision.DENY))).error().code());

                var local = takeover;
                for (int index = 0; index < 64
                        && local.runs().stream().noneMatch(run -> run.status().terminal()); index++) {
                    var event = secondClient.next(Duration.ofSeconds(5)).orElseThrow();
                    local = SessionReducer.apply(local, event, mapper);
                }
                assertEquals(RunStatus.COMPLETED, session.settled("run-1")
                        .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join().status());
                assertEquals(session.snapshot(), local);
            }
            assertEquals(InputStatus.APPLIED_TO_CONTEXT, session.input("input-1").orElseThrow().status());
            assertNotNull(session.input("input-1").orElseThrow().entryId());
            assertEquals(3, client.requests.size());
        } finally {
            registry.close(session.sessionId());
        }
    }

    @Test
    void cancellationSettlesPendingApprovalAndLateDecisionCannotExecuteTool() throws Exception {
        var mapper = new ObjectMapper();
        var client = new ReadThenStopClient(mapper);
        var registry = new SessionRegistry();
        var session = registry.create(config(client, mapper), directory.resolve("sessions"),
                new ApprovalSettings(Set.of("read"), Duration.ofSeconds(30)));
        try (var subscription = session.subscribe(session.snapshot().cursor())) {
            session.start(new RunCommand("command-1", "run-1", RunKind.PROMPT, "read it", null));
            var pending = waitForApproval(subscription, mapper);
            session.cancel("run-1");
            assertEquals(ApprovalStatus.CANCELLED,
                    session.approval(pending.approvalId()).orElseThrow().status());
            assertEquals(ErrorCode.APPROVAL_CLOSED, assertThrows(ApiException.class,
                    () -> session.resolve(new ApprovalCommand(pending.approvalId(),
                            pending.toolCallId(), pending.requestDigest(), ApprovalDecision.ALLOW)))
                    .error().code());
            assertEquals(RunStatus.CANCELLED, session.settled("run-1")
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join().status());
        } finally {
            registry.close(session.sessionId());
        }
    }

    @Test
    void approvalTimeoutDeniesTheSpecificToolRequest() throws Exception {
        var mapper = new ObjectMapper();
        var client = new ReadThenStopClient(mapper);
        var registry = new SessionRegistry();
        var session = registry.create(config(client, mapper), directory.resolve("sessions"),
                new ApprovalSettings(Set.of("read"), Duration.ofMillis(100)));
        try (var subscription = session.subscribe(session.snapshot().cursor())) {
            session.start(new RunCommand("command-1", "run-1", RunKind.PROMPT, "read it", null));
            var pending = waitForApproval(subscription, mapper);
            assertEquals(RunStatus.COMPLETED, session.settled("run-1")
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join().status());
            assertEquals(ApprovalStatus.EXPIRED,
                    session.approval(pending.approvalId()).orElseThrow().status());
            assertEquals(2, client.requests.size());
            var toolResult = assertInstanceOf(Message.ToolResultMessage.class,
                    client.requests.get(1).messages().getLast());
            assertTrue(toolResult.error());
        } finally {
            registry.close(session.sessionId());
        }
    }

    @Test
    void explicitDenialBlocksThePreparedTool() throws Exception {
        var mapper = new ObjectMapper();
        var client = new ReadThenStopClient(mapper);
        var registry = new SessionRegistry();
        var session = registry.create(config(client, mapper), directory.resolve("sessions"),
                new ApprovalSettings(Set.of("read"), Duration.ofSeconds(30)));
        try (var subscription = session.subscribe(session.snapshot().cursor())) {
            session.start(new RunCommand("command-1", "run-1", RunKind.PROMPT, "read it", null));
            var pending = waitForApproval(subscription, mapper);
            var denial = new ApprovalCommand(pending.approvalId(), pending.toolCallId(),
                    pending.requestDigest(), ApprovalDecision.DENY);
            assertEquals(ApprovalStatus.DENIED, session.resolve(denial).status());
            assertEquals(RunStatus.COMPLETED, session.settled("run-1")
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join().status());
            var toolResult = assertInstanceOf(Message.ToolResultMessage.class,
                    client.requests.get(1).messages().getLast());
            assertTrue(toolResult.error());
        } finally {
            registry.close(session.sessionId());
        }
    }

    @Test
    void concurrentClientsSettleAnApprovalOnlyOnce() throws Exception {
        Files.writeString(directory.resolve("allowed.txt"), "hello");
        var mapper = new ObjectMapper();
        var client = new ReadThenStopClient(mapper);
        var registry = new SessionRegistry();
        var session = registry.create(config(client, mapper), directory.resolve("sessions"),
                new ApprovalSettings(Set.of("read"), Duration.ofSeconds(30)));
        try (var subscription = session.subscribe(session.snapshot().cursor())) {
            session.start(new RunCommand("command-1", "run-1", RunKind.PROMPT, "read it", null));
            var pending = waitForApproval(subscription, mapper);
            var gate = new CountDownLatch(1);
            var successes = new AtomicInteger();
            var closed = new AtomicInteger();
            var allow = CompletableFuture.runAsync(() -> decide(session, pending,
                    ApprovalDecision.ALLOW, gate, successes, closed));
            var deny = CompletableFuture.runAsync(() -> decide(session, pending,
                    ApprovalDecision.DENY, gate, successes, closed));
            gate.countDown();
            allow.orTimeout(5, TimeUnit.SECONDS).join();
            deny.orTimeout(5, TimeUnit.SECONDS).join();
            assertEquals(1, successes.get());
            assertEquals(1, closed.get());
            assertTrue(session.approval(pending.approvalId()).orElseThrow().status().terminal());
            assertEquals(RunStatus.COMPLETED, session.settled("run-1")
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join().status());
            assertEquals(2, client.requests.size());
        } finally {
            registry.close(session.sessionId());
        }
    }

    private static void decide(
            ManagedSession session,
            ApprovalView approval,
            ApprovalDecision decision,
            CountDownLatch gate,
            AtomicInteger successes,
            AtomicInteger closed
    ) {
        try {
            assertTrue(gate.await(5, TimeUnit.SECONDS));
            session.resolve(new ApprovalCommand(approval.approvalId(), approval.toolCallId(),
                    approval.requestDigest(), decision));
            successes.incrementAndGet();
        } catch (ApiException rejection) {
            assertEquals(ErrorCode.APPROVAL_CLOSED, rejection.error().code());
            closed.incrementAndGet();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private ApprovalView waitForApproval(SessionSubscription subscription, ObjectMapper mapper)
            throws Exception {
        for (int index = 0; index < 32; index++) {
            var event = subscription.next(Duration.ofSeconds(5)).orElseThrow();
            if (event.type() == EventType.APPROVAL_CHANGED) {
                return mapper.treeToValue(event.data(), ApprovalView.class);
            }
        }
        throw new AssertionError("approval event was not published");
    }

    private CodingAgentConfig config(ModelClient client, ObjectMapper mapper) {
        return new CodingAgentConfig(directory, MODEL, client, mapper,
                null, null, null, null, null, null, null, null,
                null, null, null, Map.of(), null, InputDeliveryMode.RUN_SCOPED);
    }

    private static Message.Assistant assistant(List<Content> content, StopReason reason) {
        return new Message.Assistant(content, reason, null, Usage.zero(), Instant.EPOCH, MODEL);
    }

    private static final class ReadThenStopClient implements ModelClient {
        private final ObjectMapper mapper;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();

        private ReadThenStopClient(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            requests.add(request);
            Message.Assistant response = calls.getAndIncrement() == 0
                    ? assistant(List.of(new Content.ToolCall("read-1", "read",
                            mapper.createObjectNode().put("path", "allowed.txt"))),
                            StopReason.TOOL_CALL)
                    : assistant(List.of(new Content.Text("done")), StopReason.STOP);
            var stream = new AssistantMessageStream();
            stream.push(new AssistantMessageEvent.Start(response));
            stream.push(new AssistantMessageEvent.Done(response.stopReason(), response));
            return stream;
        }
    }
}
