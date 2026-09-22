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
import site.pplee.jcode.codingagent.compaction.CompactionStatus;
import site.pplee.jcode.codingagent.context.ProjectContextConfig;
import site.pplee.jcode.codingagent.event.CodingAgentEvent;
import site.pplee.jcode.codingagent.model.ModelProfile;
import site.pplee.jcode.codingagent.session.CompactionEntry;
import site.pplee.jcode.codingagent.session.BranchSummaryEntry;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;
import site.pplee.jcode.codingagent.settings.CompactionSettings;
import site.pplee.jcode.codingagent.support.ScriptedModelClient;
import site.pplee.jcode.codingagent.tool.CodingToolConfig;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

            assertThrows(CompletionException.class,
                    () -> session.compact(null).toCompletableFuture().join());
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

    private static Message.Assistant assistant(String text) {
        return new Message.Assistant(
                List.of(new Content.Text(text)), StopReason.STOP, null,
                Usage.zero(), Instant.EPOCH, MODEL);
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
}
