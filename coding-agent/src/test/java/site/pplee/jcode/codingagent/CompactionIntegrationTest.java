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
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.codingagent.compaction.CompactionStatus;
import site.pplee.jcode.codingagent.compaction.SummaryCause;
import site.pplee.jcode.codingagent.context.ProjectContextConfig;
import site.pplee.jcode.codingagent.event.CodingAgentEvent;
import site.pplee.jcode.codingagent.model.ModelProfile;
import site.pplee.jcode.codingagent.session.CompactionEntry;
import site.pplee.jcode.codingagent.session.BranchSummaryEntry;
import site.pplee.jcode.codingagent.session.SessionHeader;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;
import site.pplee.jcode.codingagent.settings.CompactionSettings;
import site.pplee.jcode.codingagent.support.ScriptedModelClient;
import site.pplee.jcode.codingagent.tool.CodingToolConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionIntegrationTest {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "compact-model");

    @TempDir
    Path directory;

    @Test
    void manualCompactionPersistsCheckpointAndOnlyChangesRequestView() {
        var client = new ScriptedModelClient(
                request -> assistant("first answer"),
                request -> assistant("second answer"),
                request -> {
                    assertTrue(request.tools().isEmpty());
                    assertEquals(12, request.options().maxOutputTokens());
                    return assistant("durable summary");
                },
                request -> {
                    assertTrue(request.messages().stream()
                            .filter(Message.User.class::isInstance)
                            .map(Message.User.class::cast)
                            .flatMap(user -> user.content().stream())
                            .filter(Content.Text.class::isInstance)
                            .map(Content.Text.class::cast)
                            .map(Content.Text::text)
                            .anyMatch(text -> text.contains("durable summary")));
                    return assistant("continued");
                });
        var config = new CodingAgentConfig(
                directory,
                MODEL,
                client,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                CodingToolConfig.readOnly(),
                ProjectContextConfig.disabled(),
                new CompactionSettings(false, 16, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(1_000), OptionalInt.of(100))));

        try (var session = new CodingAgentSession(config)) {
            session.prompt("old material ".repeat(80)).toCompletableFuture().join();
            session.prompt("recent question").toCompletableFuture().join();

            var beforeMessages = session.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .count();
            var result = session.compact("retain the current task").toCompletableFuture().join();

            assertEquals(CompactionStatus.COMPACTED, result.status());
            assertTrue(result.after().tokens() < result.before().tokens());
            assertEquals(beforeMessages, session.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .count());
            assertEquals(1, session.history().entries().stream()
                    .filter(CompactionEntry.class::isInstance)
                    .count());
            assertFalse(session.history().currentBranch().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .map(entry -> entry.message().message())
                    .filter(Message.User.class::isInstance)
                    .map(Message.User.class::cast)
                    .flatMap(user -> user.content().stream())
                    .filter(Content.Text.class::isInstance)
                    .map(Content.Text.class::cast)
                    .map(Content.Text::text)
                    .anyMatch(text -> text.contains("durable summary")));

            var continued = session.prompt("continue now").toCompletableFuture().join();
            assertEquals("continued", assertInstanceOf(
                    Content.Text.class, continued.finalMessage().content().getFirst()).text());
        }
    }

    @Test
    void classifiedOverflowCompactsAndContinuesOnceWithoutRepeatingUserInput() {
        var completedEvents = new AtomicInteger();
        var overflow = new Message.Assistant(
                List.of(), StopReason.ERROR, "context exceeded", Usage.zero(), Instant.EPOCH,
                MODEL, ResponseMetadata.of(null, null, null, ModelFailureKind.CONTEXT_OVERFLOW));
        var client = new ScriptedModelClient(
                request -> assistant("initial answer"),
                request -> overflow,
                request -> assistant("recovery summary"),
                request -> assistant("recovered answer"));
        var config = new CodingAgentConfig(
                directory,
                MODEL,
                client,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                null,
                null,
                event -> {
                    if (event instanceof site.pplee.jcode.codingagent.event.CodingAgentEvent.RunCompleted) {
                        completedEvents.incrementAndGet();
                    }
                    return java.util.concurrent.CompletableFuture.completedStage(null);
                },
                null,
                CodingToolConfig.readOnly(),
                ProjectContextConfig.disabled(),
                new CompactionSettings(true, 1_000, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(10_000), OptionalInt.of(2_000))));

        try (var session = new CodingAgentSession(config)) {
            session.prompt("establish context ".repeat(80)).toCompletableFuture().join();
            var recovered = session.prompt("do not repeat me").toCompletableFuture().join();

            assertEquals("recovered answer", assertInstanceOf(
                    Content.Text.class, recovered.finalMessage().content().getFirst()).text());
            assertEquals(4, client.requests().size());
            assertEquals(2, completedEvents.get());
            assertEquals(1, recovered.newMessages().stream()
                    .filter(site.pplee.jcode.agentcore.message.StandardAgentMessage.class::isInstance)
                    .map(site.pplee.jcode.agentcore.message.StandardAgentMessage.class::cast)
                    .map(site.pplee.jcode.agentcore.message.StandardAgentMessage::message)
                    .filter(Message.User.class::isInstance)
                    .count());
            assertTrue(recovered.newMessages().stream()
                    .filter(site.pplee.jcode.agentcore.message.StandardAgentMessage.class::isInstance)
                    .map(site.pplee.jcode.agentcore.message.StandardAgentMessage.class::cast)
                    .map(site.pplee.jcode.agentcore.message.StandardAgentMessage::message)
                    .filter(Message.Assistant.class::isInstance)
                    .map(Message.Assistant.class::cast)
                    .anyMatch(message -> message.stopReason() == StopReason.ERROR));
        }
    }

    @Test
    void secondOverflowInTheSameOperationIsNotRecoveredAgain() {
        var overflow = new Message.Assistant(
                List.of(), StopReason.ERROR, "context exceeded", Usage.zero(), Instant.EPOCH,
                MODEL, ResponseMetadata.of(null, null, null, ModelFailureKind.CONTEXT_OVERFLOW));
        var client = new ScriptedModelClient(
                request -> assistant("initial answer"),
                request -> overflow,
                request -> assistant("recovery summary"),
                request -> overflow,
                request -> {
                    throw new AssertionError("a second recovery must not start");
                });
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, null, null, CodingToolConfig.readOnly(),
                ProjectContextConfig.disabled(), new CompactionSettings(true, 1_000, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(10_000), OptionalInt.of(2_000))));

        try (var session = new CodingAgentSession(config)) {
            session.prompt("establish context ".repeat(80)).toCompletableFuture().join();

            var result = session.prompt("run once").toCompletableFuture().join();

            assertEquals(StopReason.ERROR, result.finalMessage().stopReason());
            assertEquals(4, client.requests().size());
            assertEquals(1, session.history().entries().stream()
                    .filter(CompactionEntry.class::isInstance).count());
        }
    }

    @Test
    void branchWithSummaryCommitsAtTargetAfterGeneration() {
        var client = new ScriptedModelClient(
                request -> assistant("old branch material ".repeat(500)),
                request -> assistant("departing work"),
                request -> {
                    String material = assertInstanceOf(
                            Content.Text.class, assertInstanceOf(
                                    Message.User.class, request.messages().getFirst())
                                    .content().getFirst()).text();
                    assertTrue(material.contains("[EARLIER BRANCH MATERIAL OMITTED]"));
                    assertTrue(material.contains("different direction"));
                    return assistant("work carried from the other branch");
                },
                request -> {
                    assertTrue(request.messages().stream()
                            .filter(Message.User.class::isInstance)
                            .map(Message.User.class::cast)
                            .flatMap(user -> user.content().stream())
                            .filter(Content.Text.class::isInstance)
                            .map(Content.Text.class::cast)
                            .map(Content.Text::text)
                            .anyMatch(text -> text.contains("work carried from the other branch")));
                    return assistant("branched continuation");
                });
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, null, null, CodingToolConfig.readOnly(),
                ProjectContextConfig.disabled(), new CompactionSettings(false, 16, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(1_000), OptionalInt.of(100))));

        try (var session = new CodingAgentSession(config)) {
            session.prompt("first direction").toCompletableFuture().join();
            String targetId = session.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .filter(entry -> entry.message().message() instanceof Message.User)
                    .findFirst().orElseThrow().id();
            session.prompt("different direction").toCompletableFuture().join();
            String fromId = session.history().currentEntryId().orElseThrow();

            var result = session.branchWithSummary(targetId, null).toCompletableFuture().join();

            assertTrue(result.generatedSummary());
            var entry = assertInstanceOf(
                    BranchSummaryEntry.class, session.history().currentEntry().orElseThrow());
            assertEquals(targetId, entry.parentId());
            assertEquals(fromId, entry.fromId());
            session.prompt("continue branch").toCompletableFuture().join();
        }
    }

    @Test
    void consecutiveCompactionsUpdateThePreviousSummary() {
        var client = new ScriptedModelClient(
                request -> assistant("first answer ".repeat(80)),
                request -> assistant("second answer ".repeat(80)),
                request -> assistant("summary-one"),
                request -> assistant("third answer ".repeat(80)),
                request -> {
                    String material = assertInstanceOf(
                            Content.Text.class, assertInstanceOf(
                                    Message.User.class, request.messages().getFirst())
                                    .content().getFirst()).text();
                    assertTrue(material.contains("summary-one"));
                    assertTrue(material.contains("second task"));
                    return assistant("summary-two");
                });
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, null, null, CodingToolConfig.readOnly(),
                ProjectContextConfig.disabled(), new CompactionSettings(false, 100, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(5_000), OptionalInt.of(500))));

        try (var session = new CodingAgentSession(config)) {
            session.prompt("first task ".repeat(80)).toCompletableFuture().join();
            session.prompt("second task ".repeat(80)).toCompletableFuture().join();
            session.compact(null).toCompletableFuture().join();
            session.prompt("third task ".repeat(80)).toCompletableFuture().join();

            session.compact(null).toCompletableFuture().join();

            assertEquals(2, session.history().entries().stream()
                    .filter(CompactionEntry.class::isInstance).count());
        }
    }

    @Test
    void abortCancelsManualSummaryWithoutCommittingCheckpoint() throws Exception {
        var client = new CancellableSummaryClient();
        var cancelledEvents = new AtomicInteger();
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, event -> {
                    if (event instanceof CodingAgentEvent.SummaryCancelled) {
                        cancelledEvents.incrementAndGet();
                    }
                    return CompletableFuture.completedStage(null);
                }, null, CodingToolConfig.readOnly(), ProjectContextConfig.disabled(),
                new CompactionSettings(false, 100, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(2_000), OptionalInt.of(500))));

        try (var session = new CodingAgentSession(config)) {
            session.prompt("old task ".repeat(80)).toCompletableFuture().join();
            session.prompt("recent task").toCompletableFuture().join();
            var operation = session.compact(null).toCompletableFuture();
            assertTrue(client.summaryStarted.await(5, TimeUnit.SECONDS));

            session.abort();

            var failure = assertThrows(
                    ExecutionException.class, () -> operation.get(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertEquals(0, session.history().entries().stream()
                    .filter(CompactionEntry.class::isInstance).count());
            assertEquals(1, cancelledEvents.get());
            assertEquals("after cancellation", assertInstanceOf(Content.Text.class,
                    session.prompt("continue").toCompletableFuture().join()
                            .finalMessage().content().getFirst()).text());
        }
    }

    @Test
    void closeCancelsInFlightSummaryAndSettlesItsStage() throws Exception {
        var client = new CancellableSummaryClient();
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, null, null, CodingToolConfig.readOnly(),
                ProjectContextConfig.disabled(), new CompactionSettings(false, 100, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(2_000), OptionalInt.of(500))));
        var session = new CodingAgentSession(config);
        try {
            session.prompt("old task ".repeat(80)).toCompletableFuture().join();
            session.prompt("recent task").toCompletableFuture().join();
            var operation = session.compact(null).toCompletableFuture();
            assertTrue(client.summaryStarted.await(5, TimeUnit.SECONDS));

            session.close();

            var failure = assertThrows(
                    ExecutionException.class, () -> operation.get(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
        } finally {
            session.close();
        }
    }

    @Test
    void completedEventFailureLeavesCommittedCheckpointAndReleasesAdmission() {
        var client = new ScriptedModelClient(
                request -> assistant("first answer"),
                request -> assistant("second answer"),
                request -> assistant("committed summary"),
                request -> {
                    assertTrue(request.messages().stream()
                            .filter(Message.User.class::isInstance)
                            .map(Message.User.class::cast)
                            .flatMap(message -> message.content().stream())
                            .filter(Content.Text.class::isInstance)
                            .map(Content.Text.class::cast)
                            .map(Content.Text::text)
                            .anyMatch(text -> text.contains("committed summary")));
                    return assistant("continued after callback failure");
                });
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, event -> event instanceof CodingAgentEvent.SummaryCompleted
                        ? CompletableFuture.failedStage(new IllegalStateException("sink failed"))
                        : CompletableFuture.completedStage(null),
                null, CodingToolConfig.readOnly(), ProjectContextConfig.disabled(),
                new CompactionSettings(false, 100, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(2_000), OptionalInt.of(500))));

        try (var session = new CodingAgentSession(config)) {
            session.prompt("old task ".repeat(80)).toCompletableFuture().join();
            session.prompt("recent task").toCompletableFuture().join();

            var failure = assertThrows(CompletionException.class,
                    () -> session.compact(null).toCompletableFuture().join());
            assertEquals("sink failed", failure.getCause().getMessage());
            assertEquals(1, session.history().entries().stream()
                    .filter(CompactionEntry.class::isInstance).count());
            assertEquals("continued after callback failure", assertInstanceOf(Content.Text.class,
                    session.prompt("continue").toCompletableFuture().join()
                            .finalMessage().content().getFirst()).text());
        }
    }

    @Test
    void thresholdCompactionRunsImmediatelyBeforeTheNextNormalRequest() {
        var client = new ScriptedModelClient(
                request -> assistant("first answer"),
                request -> {
                    assertTrue(request.tools().isEmpty());
                    return assistant("automatic summary");
                },
                request -> {
                    assertTrue(request.messages().stream()
                            .filter(Message.User.class::isInstance)
                            .map(Message.User.class::cast)
                            .flatMap(user -> user.content().stream())
                            .filter(Content.Text.class::isInstance)
                            .map(Content.Text.class::cast)
                            .map(Content.Text::text)
                            .anyMatch(text -> text.contains("automatic summary")));
                    return assistant("normal answer after compaction");
                });
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, null, null, CodingToolConfig.readOnly(),
                ProjectContextConfig.disabled(), new CompactionSettings(true, 1_000, 200),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(4_000), OptionalInt.of(2_000))));

        try (var session = new CodingAgentSession(config)) {
            session.prompt("a".repeat(6_000)).toCompletableFuture().join();
            var result = session.prompt("b".repeat(6_000)).toCompletableFuture().join();

            assertEquals("normal answer after compaction", assertInstanceOf(
                    Content.Text.class, result.finalMessage().content().getFirst()).text());
            assertEquals(3, client.requests().size());
            assertEquals(1, session.history().entries().stream()
                    .filter(CompactionEntry.class::isInstance).count());
        }
    }

    @Test
    void thresholdCompactionContinuesOneToolLoopAfterReverseParallelCompletion() throws Exception {
        var mapper = new ObjectMapper();
        var inflatedUsage = new Usage(5_500, 0, 0, 0, 5_500);
        var client = new ScriptedModelClient(
                request -> new Message.Assistant(
                        List.of(
                                new Content.ToolCall("first-call", "controlled", mapper.createObjectNode()),
                                new Content.ToolCall("second-call", "controlled", mapper.createObjectNode())),
                        StopReason.TOOL_CALL, null, inflatedUsage, Instant.EPOCH, MODEL),
                request -> {
                    assertTrue(request.tools().isEmpty());
                    return assistant("tool-loop summary");
                },
                request -> {
                    var resultIds = request.messages().stream()
                            .filter(Message.ToolResultMessage.class::isInstance)
                            .map(Message.ToolResultMessage.class::cast)
                            .map(Message.ToolResultMessage::toolCallId)
                            .toList();
                    assertEquals(List.of("first-call", "second-call"), resultIds);
                    return assistant("tool loop continued");
                });
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CompletableFuture<ToolExecutionResult>();
        var executions = new AtomicInteger();
        var controlled = new AgentTool<Object>() {
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
                executions.incrementAndGet();
                if (toolCallId.equals("first-call")) {
                    firstStarted.countDown();
                    return releaseFirst;
                }
                try {
                    if (!firstStarted.await(5, TimeUnit.SECONDS)) {
                        return CompletableFuture.failedStage(
                                new AssertionError("first tool call did not start"));
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedStage(failure);
                }
                return CompletableFuture.completedStage(ToolExecutionResult.success(
                        List.of(new Content.Text("second-result"))));
            }
        };
        var completionOrder = new CopyOnWriteArrayList<String>();
        var config = new CodingAgentConfig(
                directory, MODEL, client, mapper, null, null, null, null,
                null, null, event -> {
                    if (event instanceof CodingAgentEvent.RuntimeEvent runtime
                            && runtime.event() instanceof AgentEvent.ToolCompleted completed) {
                        completionOrder.add(completed.result().toolCallId());
                        if (completed.result().toolCallId().equals("second-call")) {
                            releaseFirst.complete(ToolExecutionResult.success(
                                    List.of(new Content.Text("first-result"))));
                        }
                    }
                    return CompletableFuture.completedStage(null);
                }, null, CodingToolConfig.readOnly(), ProjectContextConfig.disabled(),
                new CompactionSettings(true, 1_000, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(6_000), OptionalInt.of(2_000))));
        var manager = new SessionManager(
                new SessionHeader(UUID.randomUUID(), config.clock().instant(), directory),
                config.clock());
        var toolSet = new BuiltInTools.ToolSet(List.of(controlled), List.of());

        try (var session = new CodingAgentSession(
                config,
                (workingDirectory, ignored, revision, cancellation) ->
                        site.pplee.jcode.codingagent.context.ProjectContextSnapshot.disabled(
                                workingDirectory),
                manager,
                toolSet)) {
            var result = session.prompt(
                    "run both tools with this context " + "context ".repeat(500))
                    .toCompletableFuture().join();

            assertEquals(StopReason.STOP, result.finalMessage().stopReason(),
                    () -> "unexpected final error: " + result.finalMessage().errorMessage()
                            + ", requests=" + client.requests().size());
            assertEquals("tool loop continued", assertInstanceOf(
                    Content.Text.class, result.finalMessage().content().getFirst()).text());
            assertEquals(List.of("second-call", "first-call"), completionOrder);
            assertEquals(2, executions.get());
            assertEquals(3, client.requests().size());
            assertEquals(List.of("first-call", "second-call"), session.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .map(entry -> entry.message().message())
                    .filter(Message.ToolResultMessage.class::isInstance)
                    .map(Message.ToolResultMessage.class::cast)
                    .map(Message.ToolResultMessage::toolCallId)
                    .toList());
        }
    }

    @Test
    void overflowRecoveryDoesNotRepeatCompletedToolWork() throws Exception {
        Files.writeString(directory.resolve("once.txt"), "read-once\n");
        var mapper = new ObjectMapper();
        var overflow = new Message.Assistant(
                List.of(), StopReason.ERROR, "context exceeded", Usage.zero(), Instant.EPOCH,
                MODEL, ResponseMetadata.of(null, null, null, ModelFailureKind.CONTEXT_OVERFLOW));
        var client = new ScriptedModelClient(
                request -> assistantWithContent(List.of(new Content.ToolCall(
                        "read-once", "read", mapper.createObjectNode().put("path", "once.txt"))),
                        StopReason.TOOL_CALL),
                request -> {
                    var result = assertInstanceOf(Message.ToolResultMessage.class,
                            request.messages().getLast());
                    assertEquals("read-once", result.toolCallId());
                    return overflow;
                },
                request -> assistant("completed tool summary"),
                request -> assistant("recovered without rerunning tool"));
        var toolStarts = new AtomicInteger();
        var runCompletions = new AtomicInteger();
        var config = new CodingAgentConfig(
                directory, MODEL, client, mapper, null, null, null, null,
                null, null, event -> {
                    if (event instanceof CodingAgentEvent.RuntimeEvent runtime
                            && runtime.event() instanceof AgentEvent.ToolStarted) {
                        toolStarts.incrementAndGet();
                    } else if (event instanceof CodingAgentEvent.RunCompleted) {
                        runCompletions.incrementAndGet();
                    }
                    return CompletableFuture.completedStage(null);
                }, null, CodingToolConfig.readOnly(), ProjectContextConfig.disabled(),
                new CompactionSettings(true, 1_000, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(10_000), OptionalInt.of(2_000))));

        try (var session = new CodingAgentSession(config)) {
            var result = session.prompt(
                    "read the file once with this context " + "context ".repeat(500))
                    .toCompletableFuture().join();

            assertEquals(StopReason.STOP, result.finalMessage().stopReason(),
                    () -> "unexpected final error: " + result.finalMessage().errorMessage()
                            + ", requests=" + client.requests().size());
            assertEquals("recovered without rerunning tool", assertInstanceOf(
                    Content.Text.class, result.finalMessage().content().getFirst()).text());
            assertEquals(1, toolStarts.get());
            assertEquals(1, runCompletions.get());
            assertEquals(4, client.requests().size());
            assertEquals(1, session.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .map(entry -> entry.message().message())
                    .filter(Message.User.class::isInstance).count());
            assertEquals(1, session.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .map(entry -> entry.message().message())
                    .filter(Message.Assistant.class::isInstance)
                    .map(Message.Assistant.class::cast)
                    .filter(message -> message.stopReason() == StopReason.ERROR).count());
        }
    }

    @Test
    void repeatedCompactionWithoutNewVisibleHistoryIsSkippedWithoutCallingModel() {
        var client = new ScriptedModelClient(
                request -> assistant("first answer ".repeat(80)),
                request -> assistant("second answer ".repeat(80)),
                request -> assistant("summary-once"));
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, null, null, CodingToolConfig.readOnly(),
                ProjectContextConfig.disabled(), new CompactionSettings(false, 100, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(5_000), OptionalInt.of(500))));

        try (var session = new CodingAgentSession(config)) {
            session.prompt("first task ".repeat(80)).toCompletableFuture().join();
            session.prompt("second task ".repeat(80)).toCompletableFuture().join();
            assertEquals(CompactionStatus.COMPACTED,
                    session.compact(null).toCompletableFuture().join().status());

            assertEquals(CompactionStatus.SKIPPED,
                    session.compact(null).toCompletableFuture().join().status());
            assertEquals(3, client.requests().size());
            assertEquals(1, session.history().entries().stream()
                    .filter(CompactionEntry.class::isInstance).count());
        }
    }

    @Test
    void abortFromOverflowCompletionDoesNotStartSummaryOrRetry() {
        var overflow = new Message.Assistant(
                List.of(), StopReason.ERROR, "context exceeded", Usage.zero(), Instant.EPOCH,
                MODEL, ResponseMetadata.of(null, null, null, ModelFailureKind.CONTEXT_OVERFLOW));
        var client = new ScriptedModelClient(
                request -> assistant("initial answer"),
                request -> overflow,
                request -> {
                    throw new AssertionError("cancellation must prevent overflow summary");
                });
        var sessionRef = new AtomicReference<CodingAgentSession>();
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, event -> {
                    if (event instanceof CodingAgentEvent.RuntimeEvent runtime
                            && runtime.event() instanceof AgentEvent.MessageCompleted completed
                            && completed.message() instanceof StandardAgentMessage standard
                            && standard.message() instanceof Message.Assistant assistant
                            && assistant.metadata().failureKind()
                                    .filter(kind -> kind == ModelFailureKind.CONTEXT_OVERFLOW)
                                    .isPresent()) {
                        sessionRef.get().abort();
                    }
                    return CompletableFuture.completedStage(null);
                }, null, CodingToolConfig.readOnly(), ProjectContextConfig.disabled(),
                new CompactionSettings(true, 1_000, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(10_000), OptionalInt.of(2_000))));

        try (var session = new CodingAgentSession(config)) {
            sessionRef.set(session);
            session.prompt("establish context ".repeat(80)).toCompletableFuture().join();

            var failure = assertThrows(CompletionException.class,
                    () -> session.prompt("cancel recovery").toCompletableFuture().join());

            assertInstanceOf(CancellationException.class, failure.getCause());
            assertEquals(2, client.requests().size());
            assertEquals(0, session.history().entries().stream()
                    .filter(CompactionEntry.class::isInstance).count());
        }
    }

    @Test
    void thresholdCompletedCallbackFailureRemainsInfrastructureFailureAfterCommit() {
        var client = new ScriptedModelClient(
                request -> assistant("first answer"),
                request -> assistant("threshold summary"),
                request -> {
                    throw new AssertionError("normal request must not follow failed summary callback");
                });
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, event -> event instanceof CodingAgentEvent.SummaryCompleted completed
                        && completed.cause() == SummaryCause.THRESHOLD
                        ? CompletableFuture.failedStage(new IllegalStateException("threshold sink failed"))
                        : CompletableFuture.completedStage(null),
                null, CodingToolConfig.readOnly(), ProjectContextConfig.disabled(),
                new CompactionSettings(true, 1_000, 200),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(4_000), OptionalInt.of(2_000))));

        try (var session = new CodingAgentSession(config)) {
            session.prompt("a".repeat(6_000)).toCompletableFuture().join();

            var failure = assertThrows(CompletionException.class,
                    () -> session.prompt("b".repeat(6_000)).toCompletableFuture().join());

            assertEquals("threshold sink failed", failure.getCause().getMessage());
            assertEquals(2, client.requests().size());
            assertEquals(1, session.history().entries().stream()
                    .filter(CompactionEntry.class::isInstance).count());
            assertEquals(1, session.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .map(entry -> entry.message().message())
                    .filter(Message.Assistant.class::isInstance).count());
        }
    }

    @Test
    void overflowCompletedCallbackFailureRemainsInfrastructureFailureAfterCommit() {
        var overflow = new Message.Assistant(
                List.of(), StopReason.ERROR, "context exceeded", Usage.zero(), Instant.EPOCH,
                MODEL, ResponseMetadata.of(null, null, null, ModelFailureKind.CONTEXT_OVERFLOW));
        var client = new ScriptedModelClient(
                request -> assistant("initial answer"),
                request -> overflow,
                request -> assistant("overflow summary"),
                request -> {
                    throw new AssertionError("retry must not follow failed summary callback");
                });
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, event -> event instanceof CodingAgentEvent.SummaryCompleted completed
                        && completed.cause() == SummaryCause.OVERFLOW
                        ? CompletableFuture.failedStage(new IllegalStateException("overflow sink failed"))
                        : CompletableFuture.completedStage(null),
                null, CodingToolConfig.readOnly(), ProjectContextConfig.disabled(),
                new CompactionSettings(true, 1_000, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(10_000), OptionalInt.of(2_000))));

        try (var session = new CodingAgentSession(config)) {
            session.prompt("establish context ".repeat(80)).toCompletableFuture().join();

            var failure = assertThrows(CompletionException.class,
                    () -> session.prompt("overflow now").toCompletableFuture().join());

            assertEquals("overflow sink failed", failure.getCause().getMessage());
            assertEquals(3, client.requests().size());
            assertEquals(1, session.history().entries().stream()
                    .filter(CompactionEntry.class::isInstance).count());
        }
    }

    @Test
    void overflowCompactionWriteFailureIsExposedAndPoisonsWriterBeforeNextModelCall() throws Exception {
        var overflow = new Message.Assistant(
                List.of(), StopReason.ERROR, "context exceeded", Usage.zero(), Instant.EPOCH,
                MODEL, ResponseMetadata.of(null, null, null, ModelFailureKind.CONTEXT_OVERFLOW));
        var client = new ScriptedModelClient(
                request -> assistant("initial answer"),
                request -> overflow,
                request -> assistant("summary that cannot be stored"),
                request -> {
                    throw new AssertionError("poisoned writer must reject before another model call");
                });
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, null, null, CodingToolConfig.readOnly(),
                ProjectContextConfig.disabled(), new CompactionSettings(true, 1_000, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(10_000), OptionalInt.of(2_000))));
        Path path;
        var header = new SessionHeader(UUID.randomUUID(), config.clock().instant(), directory);
        try (var manager = SessionManager.createFileBacked(
                header, directory.resolve("write-failure"), config.clock())) {
            path = manager.filePath();
        }
        var ids = new AtomicInteger();
        var manager = SessionManager.openFileBacked(
                path, config.clock(), () -> "entry-" + ids.incrementAndGet(),
                channel -> buffer -> {
                    String record = StandardCharsets.UTF_8.decode(buffer.asReadOnlyBuffer()).toString();
                    if (record.contains("\"type\":\"compaction\"")) {
                        throw new IOException("injected compaction write failure");
                    }
                    return channel.write(buffer);
                });

        try (var session = new CodingAgentSession(
                config,
                (workingDirectory, ignored, revision, cancellation) ->
                        site.pplee.jcode.codingagent.context.ProjectContextSnapshot.disabled(
                                workingDirectory),
                manager)) {
            session.prompt("establish context ".repeat(80)).toCompletableFuture().join();

            var failure = assertThrows(CompletionException.class,
                    () -> session.prompt("overflow now").toCompletableFuture().join());

            assertTrue(rootCause(failure).getMessage().contains("injected compaction write failure"));
            assertEquals(3, client.requests().size());
            assertEquals(0, session.history().entries().stream()
                    .filter(CompactionEntry.class::isInstance).count());

            var poisoned = assertThrows(CompletionException.class,
                    () -> session.prompt("must fail before model").toCompletableFuture().join());
            assertTrue(rootCause(poisoned).getMessage().contains("uncertain"));
            assertEquals(3, client.requests().size());
        }
    }

    @Test
    void poisonedWriterRejectsManualAndBranchSummariesBeforeCallingModel() throws Exception {
        var seedClient = new ScriptedModelClient(
                request -> assistant("first answer ".repeat(80)),
                request -> assistant("second answer ".repeat(80)));
        var seedConfig = new CodingAgentConfig(
                directory, MODEL, seedClient, new ObjectMapper(), null, null, null, null,
                null, null, null, null, CodingToolConfig.readOnly(),
                ProjectContextConfig.disabled(), new CompactionSettings(false, 100, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(5_000), OptionalInt.of(500))));
        Path path;
        String targetId;
        try (var seed = CodingAgentSession.create(seedConfig, directory.resolve("poisoned-summary"))) {
            seed.prompt("first task ".repeat(80)).toCompletableFuture().join();
            targetId = seed.history().entries().stream()
                    .filter(SessionMessageEntry.class::isInstance)
                    .map(SessionMessageEntry.class::cast)
                    .filter(entry -> entry.message().message() instanceof Message.User)
                    .findFirst().orElseThrow().id();
            seed.prompt("second task ".repeat(80)).toCompletableFuture().join();
            path = seed.sessionFile().orElseThrow();
        }

        var summaryClient = new ScriptedModelClient(request -> {
            throw new AssertionError("known-invalid writer must reject before summary model");
        });
        var config = new CodingAgentConfig(
                directory, MODEL, summaryClient, new ObjectMapper(), null, null, null, null,
                null, null, null, null, CodingToolConfig.readOnly(),
                ProjectContextConfig.disabled(), new CompactionSettings(false, 100, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(5_000), OptionalInt.of(500))));
        var ids = new AtomicInteger();
        var manager = SessionManager.openFileBacked(
                path, config.clock(), () -> "poison-" + ids.incrementAndGet(),
                channel -> buffer -> {
                    throw new IOException("injected metadata write failure");
                });
        try (var session = new CodingAgentSession(
                config,
                (workingDirectory, ignored, revision, cancellation) ->
                        site.pplee.jcode.codingagent.context.ProjectContextSnapshot.disabled(
                                workingDirectory),
                manager)) {
            assertThrows(IOException.class, () -> session.setName("poison"));

            var compactFailure = assertThrows(CompletionException.class,
                    () -> session.compact(null).toCompletableFuture().join());
            assertTrue(rootCause(compactFailure).getMessage().contains("uncertain"));
            var branchFailure = assertThrows(CompletionException.class,
                    () -> session.branchWithSummary(targetId, null).toCompletableFuture().join());
            assertTrue(rootCause(branchFailure).getMessage().contains("uncertain"));
            assertTrue(summaryClient.requests().isEmpty());
        }
    }

    @Test
    void automaticCandidateMustShrinkTheRealRequestEvenWhenUsageIsMuchLarger() {
        var inflatedUsage = new Usage(35_000, 0, 0, 0, 35_000);
        var measuredAnswer = new Message.Assistant(
                List.of(new Content.Text("short measured answer")), StopReason.STOP, null,
                inflatedUsage, Instant.EPOCH, MODEL);
        var client = new ScriptedModelClient(
                request -> measuredAnswer,
                request -> assistant("summary ".repeat(3_000)),
                request -> {
                    throw new AssertionError("a larger candidate must not be sent as normal context");
                });
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, null, null, CodingToolConfig.readOnly(),
                ProjectContextConfig.disabled(), new CompactionSettings(true, 10_000, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(40_000), OptionalInt.of(20_000))));

        try (var session = new CodingAgentSession(config)) {
            session.prompt("small first request").toCompletableFuture().join();

            var result = session.prompt("small second request").toCompletableFuture().join();

            assertEquals(StopReason.ERROR, result.finalMessage().stopReason());
            assertEquals(2, client.requests().size());
            assertEquals(0, session.history().entries().stream()
                    .filter(CompactionEntry.class::isInstance).count());
        }
    }

    @Test
    void closeWaitsForNonCooperativeSummaryTerminalBeforeReleasingOwnedResource() throws Exception {
        var client = new NonCooperativeSummaryClient();
        var ownedClosed = new AtomicBoolean();
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(), null, null, null, null,
                null, null, null, null, CodingToolConfig.readOnly(),
                ProjectContextConfig.disabled(), new CompactionSettings(false, 100, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(2_000), OptionalInt.of(500))));
        var manager = new SessionManager(
                new SessionHeader(UUID.randomUUID(), config.clock().instant(), directory),
                config.clock());
        var session = new CodingAgentSession(
                config,
                (workingDirectory, ignored, revision, cancellation) ->
                        site.pplee.jcode.codingagent.context.ProjectContextSnapshot.disabled(
                                workingDirectory),
                manager,
                null,
                () -> ownedClosed.set(true));
        try {
            session.prompt("old task ".repeat(80)).toCompletableFuture().join();
            session.prompt("recent task").toCompletableFuture().join();
            var operation = session.compact(null).toCompletableFuture();
            assertTrue(client.summaryStarted.await(5, TimeUnit.SECONDS));

            session.close();

            assertFalse(operation.isDone());
            assertFalse(ownedClosed.get());
            client.completeSummary();
            var failure = assertThrows(
                    ExecutionException.class, () -> operation.get(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertTrue(ownedClosed.get());
        } finally {
            client.completeSummary();
            session.close();
        }
    }

    private static Throwable rootCause(Throwable failure) {
        var current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Message.Assistant assistant(String text) {
        return assistantWithContent(List.of(new Content.Text(text)), StopReason.STOP);
    }

    private static Message.Assistant assistantWithContent(List<Content> content, StopReason stopReason) {
        return new Message.Assistant(
                content, stopReason, null, Usage.zero(), Instant.EPOCH, MODEL);
    }

    private static AssistantMessageStream completedStream(Message.Assistant message) {
        var stream = new AssistantMessageStream();
        stream.push(new AssistantMessageEvent.Start(message));
        stream.push(new AssistantMessageEvent.Done(message.stopReason(), message));
        return stream;
    }

    private static final class CancellableSummaryClient implements ModelClient {
        private final CountDownLatch summaryStarted = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            int call = calls.incrementAndGet();
            if (call <= 2) {
                return completedStream(assistant("answer " + call));
            }
            if (call > 3) {
                return completedStream(assistant("after cancellation"));
            }
            var stream = new AssistantMessageStream();
            stream.push(new AssistantMessageEvent.Start(assistant("")));
            cancellation.onCancellation(() -> {
                var aborted = new Message.Assistant(
                        List.of(), StopReason.ABORTED, "cancelled", Usage.zero(), Instant.EPOCH, MODEL);
                stream.push(new AssistantMessageEvent.Error(StopReason.ABORTED, aborted));
            });
            summaryStarted.countDown();
            return stream;
        }
    }

    private static final class NonCooperativeSummaryClient implements ModelClient {
        private final CountDownLatch summaryStarted = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private final AssistantMessageStream summaryStream = new AssistantMessageStream();

        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            int call = calls.incrementAndGet();
            if (call <= 2) {
                return completedStream(assistant("answer " + call));
            }
            summaryStream.push(new AssistantMessageEvent.Start(assistant("")));
            summaryStarted.countDown();
            return summaryStream;
        }

        void completeSummary() {
            var summary = assistant("eventual summary");
            summaryStream.push(new AssistantMessageEvent.Done(summary.stopReason(), summary));
        }
    }
}
