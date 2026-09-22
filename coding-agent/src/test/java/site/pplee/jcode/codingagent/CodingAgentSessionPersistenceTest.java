package site.pplee.jcode.codingagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.codingagent.context.ProjectContextConfig;
import site.pplee.jcode.codingagent.context.ProjectContextSnapshot;
import site.pplee.jcode.codingagent.event.CodingAgentEvent;
import site.pplee.jcode.codingagent.event.CodingAgentEventSink;
import site.pplee.jcode.codingagent.session.ModelChangeEntry;
import site.pplee.jcode.codingagent.session.SessionContextBuilder;
import site.pplee.jcode.codingagent.session.SessionDiagnostic;
import site.pplee.jcode.codingagent.session.SessionFileLockException;
import site.pplee.jcode.codingagent.session.SessionHeader;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;
import site.pplee.jcode.codingagent.session.ThinkingLevelChangeEntry;
import site.pplee.jcode.codingagent.support.ScriptedModelClient;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingAgentSessionPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "session-model");
    private static final ModelRef OTHER_MODEL = new ModelRef("test", "scripted", "other-model");

    @TempDir
    Path directory;

    @Test
    void defaultModeRecordsCompletedMessagesBeforePublishingTheirEvents() {
        var sessionRef = new AtomicReference<CodingAgentSession>();
        var observedMessageCounts = new CopyOnWriteArrayList<Long>();
        var client = new ScriptedModelClient(request -> assistant("first"));
        var sink = (CodingAgentEventSink) event -> {
            if (event instanceof CodingAgentEvent.RuntimeEvent runtime
                    && runtime.event() instanceof AgentEvent.MessageCompleted) {
                observedMessageCounts.add(messageCount(sessionRef.get()));
            }
            return CompletableFuture.completedStage(null);
        };

        try (var session = new CodingAgentSession(config(directory, client, sink))) {
            sessionRef.set(session);
            session.prompt("hello").toCompletableFuture().join();

            assertTrue(session.sessionFile().isEmpty());
            assertTrue(session.sessionDiagnostics().isEmpty());
            assertEquals(List.of(1L, 2L), observedMessageCounts);
            assertEquals(List.of(
                            ModelChangeEntry.class,
                            ThinkingLevelChangeEntry.class,
                            SessionMessageEntry.class,
                            SessionMessageEntry.class),
                    session.history().entries().stream().map(Object::getClass).toList());
            assertEquals(List.of("hello", "first"), messageTexts(session));
        }
    }

    @Test
    void hostSinkFailureKeepsAcceptedUserAndContinueUsesRealignedTranscript() {
        var failFirstCompleted = new AtomicBoolean(true);
        var client = new ScriptedModelClient(request -> {
            assertEquals(List.of("question"), request.messages().stream()
                    .map(CodingAgentSessionPersistenceTest::text)
                    .toList());
            return assistant("continued");
        });
        var sink = (CodingAgentEventSink) event -> {
            if (event instanceof CodingAgentEvent.RuntimeEvent runtime
                    && runtime.event() instanceof AgentEvent.MessageCompleted
                    && failFirstCompleted.compareAndSet(true, false)) {
                return CompletableFuture.failedFuture(new IOException("host sink failed"));
            }
            return CompletableFuture.completedStage(null);
        };

        try (var session = new CodingAgentSession(config(directory, client, sink))) {
            var first = assertThrows(CompletionException.class,
                    () -> session.prompt("question").toCompletableFuture().join());
            assertInstanceOf(IOException.class, first.getCause());
            assertEquals(List.of("question"), messageTexts(session));
            assertTrue(client.requests().isEmpty(), "the failed user event precedes model invocation");

            session.continueRun().toCompletableFuture().join();

            assertEquals(1, client.requests().size());
            assertEquals(List.of("question", "continued"), messageTexts(session));
            assertFalse(session.isRunning());
        }
    }

    @Test
    void resetLeafPreservesPendingSteeringAfterInfrastructureFailure() {
        var failUserEvent = new AtomicBoolean(true);
        var userEventGate = new CompletableFuture<Void>();
        var userEventSeen = new CountDownLatch(1);
        var client = new ScriptedModelClient(request -> {
            assertEquals(List.of("fresh", "queued steering"), request.messages().stream()
                    .map(CodingAgentSessionPersistenceTest::text).toList());
            return assistant("final answer");
        });
        var sink = (CodingAgentEventSink) event -> {
            if (event instanceof CodingAgentEvent.RuntimeEvent runtime
                    && runtime.event() instanceof AgentEvent.MessageCompleted completed
                    && completed.message() instanceof StandardAgentMessage standard
                    && standard.message() instanceof Message.User
                    && failUserEvent.compareAndSet(true, false)) {
                userEventSeen.countDown();
                return userEventGate;
            }
            return CompletableFuture.completedStage(null);
        };

        try (var session = new CodingAgentSession(config(directory, client, sink))) {
            var failedRun = session.prompt("discarded branch");
            assertTrue(userEventSeen.await(5, TimeUnit.SECONDS));
            session.steer("queued steering");
            userEventGate.completeExceptionally(new IOException("injected sink failure"));
            assertThrows(CompletionException.class,
                    () -> failedRun.toCompletableFuture().join());

            session.resetLeaf();
            session.prompt("fresh").toCompletableFuture().join();

            assertEquals(1, client.requests().size());
            assertEquals(List.of("fresh", "queued steering", "final answer"),
                    session.history().currentBranch().stream()
                            .filter(SessionMessageEntry.class::isInstance)
                            .map(SessionMessageEntry.class::cast)
                            .map(entry -> text(entry.message().message()))
                            .toList());
            assertEquals(4, messageCount(session),
                    "the discarded branch remains queryable while reset starts a new root");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void sinkFailureAfterToolResultKeepsItAndContinueDoesNotReplayTheTool()
            throws Exception {
        Files.writeString(directory.resolve("fixture.txt"), "fixture");
        var mapper = new ObjectMapper();
        var client = new ScriptedModelClient(
                request -> new Message.Assistant(
                        List.of(new Content.ToolCall(
                                "read-call", "read",
                                mapper.createObjectNode().put("path", "fixture.txt"))),
                        StopReason.TOOL_CALL, null, Usage.zero(), NOW, MODEL),
                request -> {
                    assertEquals(3, request.messages().size());
                    assertEquals("read-call", assertInstanceOf(
                            Message.ToolResultMessage.class,
                            request.messages().getLast()).toolCallId());
                    return assistant("continued-after-tool");
                });
        var failToolResult = new AtomicBoolean(true);
        var sink = (CodingAgentEventSink) event -> {
            if (event instanceof CodingAgentEvent.RuntimeEvent runtime
                    && runtime.event() instanceof AgentEvent.MessageCompleted completed
                    && completed.message() instanceof StandardAgentMessage standard
                    && standard.message() instanceof Message.ToolResultMessage
                    && failToolResult.compareAndSet(true, false)) {
                return CompletableFuture.failedFuture(new IOException("tool result observer failed"));
            }
            return CompletableFuture.completedStage(null);
        };

        try (var session = new CodingAgentSession(config(directory, client, sink))) {
            assertThrows(CompletionException.class,
                    () -> session.prompt("read").toCompletableFuture().join());
            assertEquals(3, messageCount(session));
            assertEquals(1, client.requests().size());

            session.continueRun().toCompletableFuture().join();

            assertEquals(2, client.requests().size());
            assertEquals(4, messageCount(session));
            var finalMessage = session.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .map(entry -> entry.message().message())
                    .reduce((ignored, latest) -> latest)
                    .orElseThrow();
            assertEquals("continued-after-tool", text(finalMessage));
        }
    }

    @Test
    void createCloseOpenRestoresMessagesAndExclusiveOwnership() throws Exception {
        Path sessionPath;
        var firstClient = new ScriptedModelClient(request -> assistant("answer-one"));
        var firstConfig = config(directory, firstClient, null);
        try (var session = CodingAgentSession.create(firstConfig, directory.resolve("sessions"))) {
            session.prompt("question-one").toCompletableFuture().join();
            sessionPath = session.sessionFile().orElseThrow();
            assertThrows(SessionFileLockException.class,
                    () -> CodingAgentSession.open(firstConfig, sessionPath));
        }

        var secondClient = new ScriptedModelClient(request -> {
            assertEquals(List.of("question-one", "answer-one", "question-two"),
                    request.messages().stream()
                            .map(CodingAgentSessionPersistenceTest::text)
                            .toList());
            return assistant("answer-two");
        });
        try (var reopened = CodingAgentSession.open(
                config(directory, secondClient, null), sessionPath)) {
            assertEquals(List.of("question-one", "answer-one"), messageTexts(reopened));
            reopened.prompt("question-two").toCompletableFuture().join();
            assertEquals(List.of("question-one", "answer-one", "question-two", "answer-two"),
                    messageTexts(reopened));
        }

        try (var verified = CodingAgentSession.open(
                config(directory, new ScriptedModelClient(), null), sessionPath)) {
            assertEquals(6, verified.history().entries().size());
            assertEquals(4, messageCount(verified));
        }
    }

    @Test
    void currentConfigRemainsAuthoritativeAndItsModelChangePrecedesTheNextMessage()
            throws Exception {
        Path path;
        try (var created = CodingAgentSession.create(
                config(directory, new ScriptedModelClient(request -> assistant("old")), null),
                directory.resolve("sessions"))) {
            created.prompt("first").toCompletableFuture().join();
            path = created.sessionFile().orElseThrow();
        }

        var client = new ScriptedModelClient(request -> {
            assertEquals(OTHER_MODEL, request.model());
            return new Message.Assistant(
                    List.of(new Content.Text("new")), StopReason.STOP, null,
                    Usage.zero(), NOW, OTHER_MODEL);
        });
        try (var reopened = CodingAgentSession.open(
                config(directory, OTHER_MODEL, client, null), path)) {
            reopened.prompt("second").toCompletableFuture().join();
            assertEquals(List.of(MODEL, OTHER_MODEL), reopened.history().entries().stream()
                    .filter(ModelChangeEntry.class::isInstance)
                    .map(ModelChangeEntry.class::cast)
                    .map(ModelChangeEntry::model)
                    .toList());
            var entries = reopened.history().entries();
            int currentModel = java.util.stream.IntStream.range(0, entries.size())
                    .filter(index -> entries.get(index) instanceof ModelChangeEntry change
                            && change.model().equals(OTHER_MODEL))
                    .findFirst().orElseThrow();
            int secondUser = java.util.stream.IntStream.range(0, entries.size())
                    .filter(index -> entries.get(index) instanceof SessionMessageEntry message
                            && message.message().message() instanceof Message.User user
                            && text(user).equals("second"))
                    .findFirst().orElseThrow();
            assertEquals(currentModel + 1, secondUser);
        }
    }

    @Test
    void openReportsWorkingDirectoryAndRecoverableTailDiagnostics() throws Exception {
        Path sessionPath;
        try (var session = CodingAgentSession.create(
                config(directory, new ScriptedModelClient(), null),
                directory.resolve("sessions"))) {
            sessionPath = session.sessionFile().orElseThrow();
        }
        Files.writeString(sessionPath, "{\"type\":\"message\"", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        var otherDirectory = Files.createDirectory(directory.resolve("other"));

        try (var reopened = CodingAgentSession.open(
                config(otherDirectory, new ScriptedModelClient(), null), sessionPath)) {
            assertEquals(2, reopened.sessionDiagnostics().size());
            assertInstanceOf(SessionDiagnostic.WorkingDirectoryMismatch.class,
                    reopened.sessionDiagnostics().get(0));
            assertInstanceOf(SessionDiagnostic.RecoveredTail.class,
                    reopened.sessionDiagnostics().get(1));
        }
        assertTrue(Files.readString(sessionPath).endsWith("{\"type\":\"message\""),
                "open and close without append must not rewrite a recoverable tail");
    }

    @Test
    void toolCompletionEventsDoNotPersistAndToolResultsFollowTranscriptSourceOrder()
            throws Exception {
        var mapper = new ObjectMapper();
        var client = new ScriptedModelClient(
                request -> new Message.Assistant(
                        List.of(
                                new Content.ToolCall("first-call", "controlled",
                                        mapper.createObjectNode()),
                                new Content.ToolCall("second-call", "controlled",
                                        mapper.createObjectNode())),
                        StopReason.TOOL_CALL, null, Usage.zero(), NOW, MODEL),
                request -> {
                    assertEquals("first-call", assertInstanceOf(
                            Message.ToolResultMessage.class, request.messages().get(2)).toolCallId());
                    assertEquals("second-call", assertInstanceOf(
                            Message.ToolResultMessage.class, request.messages().get(3)).toolCallId());
                    return assistant("done");
                });
        var firstCallStarted = new CountDownLatch(1);
        var releaseFirstCall = new CompletableFuture<ToolExecutionResult>();
        var controlledTool = new AgentTool<Object>() {
            @Override
            public String name() {
                return "controlled";
            }

            @Override
            public Class<Object> argumentType() {
                return Object.class;
            }

            @Override
            public java.util.concurrent.CompletionStage<ToolExecutionResult> execute(
                    String toolCallId,
                    Object arguments,
                    ToolUpdateSink updates,
                    CancellationSignal cancellation
            ) {
                if (toolCallId.equals("first-call")) {
                    firstCallStarted.countDown();
                    return releaseFirstCall;
                }
                try {
                    if (!firstCallStarted.await(5, TimeUnit.SECONDS)) {
                        return CompletableFuture.failedFuture(
                                new AssertionError("first tool call did not start"));
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(failure);
                }
                return CompletableFuture.completedStage(ToolExecutionResult.success(
                        List.of(new Content.Text("second"))));
            }
        };
        var toolSet = new BuiltInTools.ToolSet(List.of(controlledTool), List.of());
        var sessionRef = new AtomicReference<CodingAgentSession>();
        var countsAtToolCompletion = new CopyOnWriteArrayList<Long>();
        var toolCompletionOrder = new CopyOnWriteArrayList<String>();
        var sink = (CodingAgentEventSink) event -> {
            if (event instanceof CodingAgentEvent.RuntimeEvent runtime
                    && runtime.event() instanceof AgentEvent.ToolCompleted completed) {
                toolCompletionOrder.add(completed.result().toolCallId());
                countsAtToolCompletion.add(messageCount(sessionRef.get()));
                if (completed.result().toolCallId().equals("second-call")) {
                    releaseFirstCall.complete(ToolExecutionResult.success(
                            List.of(new Content.Text("first"))));
                }
            }
            return CompletableFuture.completedStage(null);
        };

        var manager = new SessionManager(
                new SessionHeader(UUID.randomUUID(), NOW, directory), CLOCK);
        try (var session = new CodingAgentSession(
                config(directory, client, sink),
                (workingDirectory, ignored, revision, cancellation) ->
                        ProjectContextSnapshot.disabled(workingDirectory),
                manager,
                toolSet)) {
            sessionRef.set(session);
            session.prompt("read both").toCompletableFuture().join();

            assertEquals(List.of("second-call", "first-call"), toolCompletionOrder);
            assertEquals(List.of(2L, 2L), countsAtToolCompletion);
            var toolResultIds = session.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .map(entry -> entry.message().message())
                    .filter(Message.ToolResultMessage.class::isInstance)
                    .map(Message.ToolResultMessage.class::cast)
                    .map(Message.ToolResultMessage::toolCallId)
                    .toList();
            assertEquals(List.of("first-call", "second-call"), toolResultIds);
            assertEquals(5, messageCount(session));
        }
    }

    @Test
    void terminalErrorAssistantIsAcceptedHistory() {
        var error = new Message.Assistant(
                List.of(new Content.Text("partial")),
                StopReason.ERROR,
                "provider failed",
                Usage.zero(),
                NOW,
                MODEL);
        var client = new ScriptedModelClient(request -> error);

        try (var session = new CodingAgentSession(config(directory, client, null))) {
            var result = session.prompt("fail").toCompletableFuture().join();
            assertEquals(StopReason.ERROR, result.finalMessage().stopReason());
            var stored = session.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .map(entry -> entry.message().message())
                    .toList();
            assertEquals(2, stored.size());
            assertEquals(error, stored.getLast());
        }
    }

    @Test
    void fileWriterFailureRejectsEntryRealignsToAcceptedPrefixAndPoisonsOwner() throws Exception {
        Path path;
        var header = new SessionHeader(
                UUID.fromString("00000000-0000-0000-0000-000000000099"), NOW, directory);
        try (var file = SessionFile.create(directory.resolve("sessions"), header)) {
            path = file.path();
        }
        var ids = new ArrayDeque<>(List.of("model", "thinking", "user", "next"));
        var manager = SessionManager.openFileBacked(
                path,
                CLOCK,
                ids::remove,
                channel -> buffer -> {
                    throw new IOException("injected product write failure");
                });
        var client = new ScriptedModelClient(request -> assistant("must-not-run"));

        try (var session = new CodingAgentSession(
                config(directory, client, null),
                (workingDirectory, ignored, revision, cancellation) ->
                        ProjectContextSnapshot.disabled(workingDirectory),
                manager)) {
            var first = assertThrows(CompletionException.class,
                    () -> session.prompt("first").toCompletableFuture().join());
            assertTrue(rootMessage(first).contains("injected product write failure"));
            assertTrue(session.history().entries().isEmpty());
            assertTrue(client.requests().isEmpty());

            var second = assertThrows(CompletionException.class,
                    () -> session.prompt("second").toCompletableFuture().join());
            assertTrue(rootMessage(second).contains("uncertain"));
            assertTrue(session.history().entries().isEmpty());
            assertTrue(client.requests().isEmpty());
        }
    }

    @Test
    void poisonedWriterRejectsContinueBeforeCallingModel() throws Exception {
        Path path;
        var header = new SessionHeader(
                UUID.fromString("00000000-0000-0000-0000-000000000098"), NOW, directory);
        try (var manager = SessionManager.createFileBacked(
                header, directory.resolve("sessions"), CLOCK, () -> "user")) {
            manager.appendMessage(StandardAgentMessage.of(new Message.User(
                    List.of(new Content.Text("continue me")), NOW)));
            path = manager.filePath();
        }

        var generatedIds = new AtomicInteger();
        var manager = SessionManager.openFileBacked(
                path,
                CLOCK,
                () -> "name-" + generatedIds.incrementAndGet(),
                channel -> buffer -> {
                    throw new IOException("injected metadata write failure");
                });
        var client = new ScriptedModelClient(request -> assistant("must-not-run"));
        try (var session = new CodingAgentSession(
                config(directory, client, null),
                (workingDirectory, ignored, revision, cancellation) ->
                        ProjectContextSnapshot.disabled(workingDirectory),
                manager)) {
            var writeFailure = assertThrows(IOException.class, () -> session.setName("poison"));
            assertTrue(writeFailure.getMessage().contains("injected metadata write failure"));
            assertEquals(1, generatedIds.get());

            var metadataFailure = assertThrows(IOException.class, () -> session.setName("again"));
            assertTrue(metadataFailure.getMessage().contains("uncertain"));
            assertEquals(1, generatedIds.get(),
                    "a poisoned writer must reject metadata before generating another entry id");

            var continueFailure = assertThrows(CompletionException.class,
                    () -> session.continueRun().toCompletableFuture().join());
            assertTrue(rootMessage(continueFailure).contains("uncertain"));
            assertTrue(client.requests().isEmpty());
        }
    }

    @Test
    void closedWriterChannelRejectsContinueBeforeCallingModel() throws Exception {
        Path path;
        var header = new SessionHeader(
                UUID.fromString("00000000-0000-0000-0000-000000000097"), NOW, directory);
        try (var manager = SessionManager.createFileBacked(
                header, directory.resolve("sessions"), CLOCK, () -> "user")) {
            manager.appendMessage(StandardAgentMessage.of(new Message.User(
                    List.of(new Content.Text("continue me")), NOW)));
            path = manager.filePath();
        }

        var writerChannel = new AtomicReference<FileChannel>();
        var manager = SessionManager.openFileBacked(
                path,
                CLOCK,
                () -> "unused",
                channel -> {
                    writerChannel.set(channel);
                    return channel::write;
                });
        var client = new ScriptedModelClient(request -> assistant("must-not-run"));
        try (var session = new CodingAgentSession(
                config(directory, client, null),
                (workingDirectory, ignored, revision, cancellation) ->
                        ProjectContextSnapshot.disabled(workingDirectory),
                manager)) {
            writerChannel.get().close();

            var failure = assertThrows(CompletionException.class,
                    () -> session.continueRun().toCompletableFuture().join());

            assertTrue(rootMessage(failure).contains("no longer usable"));
            assertTrue(client.requests().isEmpty());
        }
    }

    @Test
    void cancellingTheObservationFutureDoesNotCancelOrReleaseTheAcceptedRun() throws Exception {
        var callCount = new AtomicInteger();
        var started = new CountDownLatch(1);
        var firstStream = new AtomicReference<AssistantMessageStream>();
        var cancellationObserved = new AtomicBoolean();
        var runCompletedEntered = new CountDownLatch(1);
        var releaseRunCompleted = new CompletableFuture<Void>();
        ModelClient client = (request, cancellation) -> {
            if (callCount.incrementAndGet() == 1) {
                var stream = new AssistantMessageStream();
                firstStream.set(stream);
                cancellation.onCancellation(() -> cancellationObserved.set(true));
                started.countDown();
                return stream;
            }
            var stream = new AssistantMessageStream();
            var answer = assistant("second-answer");
            stream.push(new AssistantMessageEvent.Start(answer));
            stream.push(new AssistantMessageEvent.Done(answer.stopReason(), answer));
            return stream;
        };
        var sink = (CodingAgentEventSink) event -> {
            if (event instanceof CodingAgentEvent.RunCompleted) {
                runCompletedEntered.countDown();
                return releaseRunCompleted;
            }
            return CompletableFuture.completedStage(null);
        };

        try (var session = new CodingAgentSession(config(directory, client, sink))) {
            var observation = session.prompt("first").toCompletableFuture();
            assertTrue(started.await(5, TimeUnit.SECONDS));

            assertTrue(observation.cancel(true));
            assertFalse(cancellationObserved.get());
            assertThrows(IllegalStateException.class, () -> session.prompt("too-early"));
            assertEquals(List.of("first"), messageTexts(session));

            var answer = assistant("first-answer");
            firstStream.get().push(new AssistantMessageEvent.Start(answer));
            firstStream.get().push(new AssistantMessageEvent.Done(answer.stopReason(), answer));
            assertTrue(runCompletedEntered.await(5, TimeUnit.SECONDS));
            releaseRunCompleted.complete(null);
            session.close();

            assertFalse(session.isRunning());
            assertEquals(List.of("first", "first-answer"), messageTexts(session));
        }
    }

    @Test
    void closeDuringRunPersistsRealAbortedMessageBeforeReleasingFileLock() throws Exception {
        var started = new CountDownLatch(1);
        ModelClient client = (request, cancellation) -> {
            var stream = new AssistantMessageStream();
            var partial = new Message.Assistant(
                    List.of(new Content.Text("partial")), StopReason.STOP, null,
                    Usage.zero(), NOW, MODEL);
            var aborted = new Message.Assistant(
                    List.of(new Content.Text("partial")), StopReason.ABORTED, null,
                    Usage.zero(), NOW, MODEL);
            stream.push(new AssistantMessageEvent.Start(partial));
            cancellation.onCancellation(() ->
                    stream.push(new AssistantMessageEvent.Error(StopReason.ABORTED, aborted)));
            started.countDown();
            return stream;
        };
        var config = config(directory, client, null);
        var session = CodingAgentSession.create(config, directory.resolve("sessions"));
        var path = session.sessionFile().orElseThrow();
        var result = session.prompt("wait");
        assertTrue(started.await(5, TimeUnit.SECONDS));

        session.close();

        assertTrue(result.toCompletableFuture().join().aborted());
        assertEquals(StopReason.ABORTED,
                assertInstanceOf(Message.Assistant.class,
                        ((SessionMessageEntry) session.history().entries().getLast())
                                .message().message()).stopReason());
        try (var reopened = CodingAgentSession.open(
                config(directory, new ScriptedModelClient(), null), path)) {
            assertEquals(2, messageCount(reopened));
        }
    }

    @Test
    void closeRetainsWriterUntilAcceptedMetadataAppendFinishes() throws Exception {
        Path path;
        var header = new SessionHeader(
                UUID.fromString("00000000-0000-0000-0000-000000000088"), NOW, directory);
        try (var file = SessionFile.create(directory.resolve("sessions"), header)) {
            path = file.path();
        }
        var writeEntered = new CountDownLatch(1);
        var releaseWrite = new CountDownLatch(1);
        var manager = SessionManager.openFileBacked(
                path,
                CLOCK,
                () -> "name",
                channel -> buffer -> {
                    writeEntered.countDown();
                    try {
                        if (!releaseWrite.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("timed out waiting to release metadata write");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("metadata write interrupted", e);
                    }
                    return channel.write(buffer);
                });
        var session = new CodingAgentSession(
                config(directory, new ScriptedModelClient(), null),
                (workingDirectory, ignored, revision, cancellation) ->
                        ProjectContextSnapshot.disabled(workingDirectory),
                manager);
        var appendResult = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                session.setName("accepted");
                appendResult.complete(null);
            } catch (Throwable failure) {
                appendResult.completeExceptionally(failure);
            }
        });

        assertTrue(writeEntered.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> session.prompt("busy"));
        session.close();
        assertThrows(SessionFileLockException.class, () -> SessionFile.open(path));

        releaseWrite.countDown();
        appendResult.join();
        var loaded = SessionFileAccess.read(path);
        assertEquals("accepted", new SessionManager(
                loaded.header(), loaded.entries(), CLOCK, () -> "unused")
                .snapshot().name().orElseThrow());
    }

    @Test
    void branchContinueResetAndMetadataUseOneTreeWithoutChangingOldBranches() throws Exception {
        var client = new ScriptedModelClient(
                request -> {
                    assertEquals(List.of("question"), request.messages().stream()
                            .map(CodingAgentSessionPersistenceTest::text).toList());
                    return assistant("original");
                },
                request -> {
                    assertEquals(List.of("question"), request.messages().stream()
                            .map(CodingAgentSessionPersistenceTest::text).toList());
                    return assistant("alternate");
                },
                request -> {
                    assertEquals(List.of("replacement"), request.messages().stream()
                            .map(CodingAgentSessionPersistenceTest::text).toList());
                    return assistant("replacement-answer");
                });

        try (var session = new CodingAgentSession(config(directory, client, null))) {
            session.prompt("question").toCompletableFuture().join();
            var original = session.history();
            var messages = original.entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .toList();
            var user = messages.getFirst();
            var originalAssistant = messages.getLast();

            session.branch(user.id());
            session.continueRun().toCompletableFuture().join();
            var branched = session.history();
            assertEquals(2, branched.children(user.id()).stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .count());
            assertEquals(List.of("question", "alternate"), branched.currentBranch().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .map(entry -> text(entry.message().message()))
                    .toList());
            assertTrue(branched.entry(originalAssistant.id()).isPresent());

            session.branch(user.parentId());
            session.prompt("replacement").toCompletableFuture().join();
            assertEquals(List.of("replacement", "replacement-answer"),
                    session.history().currentBranch().stream()
                            .filter(SessionMessageEntry.class::isInstance)
                            .map(SessionMessageEntry.class::cast)
                            .map(entry -> text(entry.message().message()))
                            .toList());

            session.setName("demo");
            session.setLabel(user.id(), "start");
            assertEquals("demo", session.history().name().orElseThrow());
            assertEquals("start", session.history().label(user.id()).orElseThrow());
            assertEquals(List.of("replacement", "replacement-answer"),
                    SessionContextBuilder.build(session.history()).messages().stream()
                            .map(StandardAgentMessage.class::cast)
                            .map(StandardAgentMessage::message)
                            .map(CodingAgentSessionPersistenceTest::text)
                            .toList());

            session.setName(null);
            session.setLabel(user.id(), null);
            assertTrue(session.history().name().isEmpty());
            assertTrue(session.history().label(user.id()).isEmpty());

            session.resetLeaf();
            assertTrue(session.history().currentBranch().isEmpty());
            assertEquals(5, messageCount(session), "reset must retain every existing branch");
        }
    }

    @Test
    void historyOperationsAreRejectedWhileRunIsActive() throws Exception {
        var started = new CountDownLatch(1);
        ModelClient client = (request, cancellation) -> {
            var stream = new AssistantMessageStream();
            var partial = assistant("partial");
            stream.push(new AssistantMessageEvent.Start(partial));
            cancellation.onCancellation(() -> stream.push(new AssistantMessageEvent.Error(
                    StopReason.ABORTED,
                    new Message.Assistant(partial.content(), StopReason.ABORTED, null,
                            partial.usage(), partial.timestamp(), partial.sourceModel()))));
            started.countDown();
            return stream;
        };

        try (var session = new CodingAgentSession(config(directory, client, null))) {
            var run = session.prompt("question");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var userId = session.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .findFirst()
                    .orElseThrow()
                    .id();

            assertThrows(IllegalStateException.class, () -> session.branch(userId));
            assertThrows(IllegalStateException.class, session::resetLeaf);
            assertThrows(IllegalStateException.class, () -> session.setName("busy"));
            assertThrows(IllegalStateException.class, () -> session.setLabel(userId, "busy"));

            session.abort();
            assertTrue(run.toCompletableFuture().join().aborted());
        }
    }

    @Test
    void openingUnfinishedToolCallPreservesHistoryWithoutExecutingIt() throws Exception {
        var sessions = directory.resolve("sessions");
        var header = new SessionHeader(
                UUID.fromString("00000000-0000-0000-0000-000000000077"), NOW, directory);
        Path path;
        try (var file = SessionFile.create(sessions, header)) {
            file.append(new SessionMessageEntry(
                    "user", null, NOW,
                    StandardAgentMessage.of(new Message.User(
                            List.of(new Content.Text("write a file")), NOW))));
            file.append(new SessionMessageEntry(
                    "assistant", "user", NOW,
                    StandardAgentMessage.of(new Message.Assistant(
                            List.of(new Content.ToolCall(
                                    "call-1", "write", new ObjectMapper().readTree("{\"path\":\"x\"}"))),
                            StopReason.TOOL_CALL,
                            null,
                            Usage.zero(),
                            NOW,
                            MODEL))));
            path = file.path();
        }
        var before = Files.readAllBytes(path);
        var client = new ScriptedModelClient(request -> assistant("must-not-run"));

        try (var session = CodingAgentSession.open(config(directory, client, null), path)) {
            assertEquals(List.of("write a file", "write"), session.history().currentBranch().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .map(entry -> {
                        var message = entry.message().message();
                        if (message instanceof Message.User user) {
                            return ((Content.Text) user.content().getFirst()).text();
                        }
                        var assistant = (Message.Assistant) message;
                        return ((Content.ToolCall) assistant.content().getFirst()).name();
                    })
                    .toList());
            assertTrue(client.requests().isEmpty());
            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(path)),
                    "open must not synthesize a tool result or rewrite JSONL");
        }
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(path)),
                "close without a run must leave the unfinished history unchanged");
    }

    @Test
    void fileSessionCanRestoreContinueAndBranchWithoutReplayingOldWork() throws Exception {
        var sessions = directory.resolve("sessions");
        Path path;
        String userId;
        String originalAssistantId;
        try (var created = CodingAgentSession.create(
                config(directory, new ScriptedModelClient(request -> assistant("original")), null),
                sessions)) {
            created.prompt("question").toCompletableFuture().join();
            path = created.sessionFile().orElseThrow();
            var messageEntries = created.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .toList();
            userId = messageEntries.getFirst().id();
            originalAssistantId = messageEntries.getLast().id();
        }

        var continueClient = new ScriptedModelClient(request -> {
            assertEquals(List.of("question"), request.messages().stream()
                    .map(CodingAgentSessionPersistenceTest::text).toList());
            return assistant("alternate");
        });
        try (var reopened = CodingAgentSession.open(config(directory, continueClient, null), path)) {
            reopened.branch(userId);
            reopened.continueRun().toCompletableFuture().join();
        }

        var branchClient = new ScriptedModelClient(request -> {
            assertEquals(List.of("question", "original", "new question"),
                    request.messages().stream()
                            .map(CodingAgentSessionPersistenceTest::text)
                            .toList());
            return assistant("new answer");
        });
        try (var reopened = CodingAgentSession.open(config(directory, branchClient, null), path)) {
            assertEquals(List.of("question", "alternate"), reopened.history().currentBranch().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .map(entry -> text(entry.message().message()))
                    .toList());

            reopened.branch(originalAssistantId);
            reopened.prompt("new question").toCompletableFuture().join();

            assertEquals(5, messageCount(reopened));
            assertTrue(reopened.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .anyMatch(entry -> text(entry.message().message()).equals("alternate")));
        }
    }

    @Test
    void continueRejectsEmptyOrAssistantLeafWithoutChangingHistory() {
        try (var empty = new CodingAgentSession(
                config(directory, new ScriptedModelClient(), null))) {
            assertThrows(CompletionException.class,
                    () -> empty.continueRun().toCompletableFuture().join());
            assertTrue(empty.history().entries().isEmpty());
        }

        var client = new ScriptedModelClient(request -> assistant("answer"));
        try (var session = new CodingAgentSession(config(directory, client, null))) {
            session.prompt("question").toCompletableFuture().join();
            var before = session.history().entries();
            assertThrows(CompletionException.class,
                    () -> session.continueRun().toCompletableFuture().join());
            assertEquals(before, session.history().entries());
        }
    }

    private CodingAgentConfig config(Path workingDirectory, ModelClient client, CodingAgentEventSink sink) {
        return config(workingDirectory, MODEL, client, sink);
    }

    private CodingAgentConfig config(
            Path workingDirectory,
            ModelRef model,
            ModelClient client,
            CodingAgentEventSink sink
    ) {
        return new CodingAgentConfig(
                workingDirectory,
                model,
                client,
                new ObjectMapper(),
                ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults(),
                QueueMode.ONE_AT_A_TIME,
                QueueMode.ONE_AT_A_TIME,
                null,
                null,
                sink,
                CLOCK,
                null,
                ProjectContextConfig.disabled());
    }

    private static Message.Assistant assistant(String text) {
        return new Message.Assistant(
                List.of(new Content.Text(text)), StopReason.STOP, null,
                Usage.zero(), NOW, MODEL);
    }

    private static long messageCount(CodingAgentSession session) {
        return session.history().entries().stream()
                .filter(SessionMessageEntry.class::isInstance)
                .count();
    }

    private static List<String> messageTexts(CodingAgentSession session) {
        return session.history().entries().stream()
                .filter(SessionMessageEntry.class::isInstance)
                .map(SessionMessageEntry.class::cast)
                .map(entry -> text(entry.message().message()))
                .toList();
    }

    private static String text(Message message) {
        return switch (message) {
            case Message.User user -> ((Content.Text) user.content().getFirst()).text();
            case Message.Assistant assistant -> ((Content.Text) assistant.content().getFirst()).text();
            case Message.ToolResultMessage result -> ((Content.Text) result.content().getFirst()).text();
        };
    }

    private static String rootMessage(Throwable failure) {
        var current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}
