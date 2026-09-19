package site.pplee.jcode.codingagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.CacheRetention;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.client.PromptCacheOptions;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.agentcore.AgentContext;
import site.pplee.jcode.agentcore.LoopResult;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.codingagent.event.CodingAgentEvent;
import site.pplee.jcode.codingagent.support.ScriptedModelClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CodingAgentSessionTest {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "model");

    @TempDir
    Path directory;

    @Test
    void configNormalizesPathAndAppliesDefaultsWithoutReadingEnvironment() {
        var client = new ScriptedModelClient(request -> assistant("ok"));
        var config = new CodingAgentConfig(directory.resolve("."), MODEL, client,
                new ObjectMapper(), null, null, null, null,
                null, null, null, null);

        assertEquals(directory.toAbsolutePath().normalize(), config.workingDirectory());
        assertNotNull(config.thinkingLevel());
        assertNotNull(config.requestOptions());
        assertNotNull(config.eventSink());
        assertNotNull(config.clock());
    }

    @Test
    void configRejectsRequiredNullsAndRedactsPromptAndCacheValues() throws Exception {
        var client = new ScriptedModelClient(request -> assistant("ok"));
        var mapper = new ObjectMapper();
        assertThrows(NullPointerException.class, () -> new CodingAgentConfig(
                null, MODEL, client, mapper, null, null, null, null,
                null, null, null, null));
        assertThrows(NullPointerException.class, () -> new CodingAgentConfig(
                directory, null, client, mapper, null, null, null, null,
                null, null, null, null));
        assertThrows(NullPointerException.class, () -> new CodingAgentConfig(
                directory, MODEL, null, mapper, null, null, null, null,
                null, null, null, null));
        assertThrows(NullPointerException.class, () -> new CodingAgentConfig(
                directory, MODEL, client, null, null, null, null, null,
                null, null, null, null));
        Path file = Files.createFile(directory.resolve("file.txt"));
        assertThrows(IllegalArgumentException.class, () -> new CodingAgentConfig(
                file, MODEL, client, mapper, null, null, null, null,
                null, null, null, null));

        var options = ModelRequestOptions.defaults().withPromptCache(new PromptCacheOptions(
                CacheRetention.SHORT, "cache-secret", "session-secret"));
        var config = new CodingAgentConfig(
                directory, MODEL, client, mapper, null, options, null, null,
                "prompt-secret", "append-secret", null, null);
        String diagnostic = config.toString();
        assertFalse(diagnostic.contains("prompt-secret"));
        assertFalse(diagnostic.contains("append-secret"));
        assertFalse(diagnostic.contains("cache-secret"));
        assertFalse(diagnostic.contains("session-secret"));
    }

    @Test
    void rejectsInvalidConfigAndEmptyPromptButPreservesWhitespacePrompt() {
        var client = new ScriptedModelClient(request -> {
            var user = assertInstanceOf(Message.User.class, request.messages().getFirst());
            assertEquals("   ", assertInstanceOf(Content.Text.class, user.content().getFirst()).text());
            return assistant("ok");
        });
        assertThrows(IllegalArgumentException.class, () -> new CodingAgentConfig(
                directory.resolve("missing"), MODEL, client, new ObjectMapper(),
                null, null, null, null, null, null, null, null));

        try (var session = new CodingAgentSession(new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                null, null, null, null, null, null, null, null))) {
            assertThrows(IllegalArgumentException.class, () -> session.prompt(""));
            assertEquals("ok", text(session.prompt("   ").toCompletableFuture().join().finalMessage()));
        }
    }

    @Test
    void concurrentPromptFailsFastAndSessionQueuesOnlyWhileRunning() throws Exception {
        var client = new BlockingModelClient();
        try (var session = new CodingAgentSession(new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                null, null, null, null, null, null, null, null))) {
            var first = session.prompt("first");
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            assertTrue(session.isRunning());
            assertThrows(IllegalStateException.class, () -> session.prompt("second"));
            session.steer("steer");
            session.followUp("follow");
            client.complete();
            first.toCompletableFuture().join();
            assertFalse(session.isRunning());
            assertEquals(3, client.requests.size());
            assertEquals("steer", lastUserText(client.requests.get(1)));
            assertEquals("follow", lastUserText(client.requests.get(2)));
            assertThrows(IllegalStateException.class, () -> session.steer("late"));
        }
    }

    @Test
    void messageAcceptedDuringCompletionBackpressureRemainsForTheNextRun() throws Exception {
        var completionReached = new CountDownLatch(1);
        var completionGate = new CompletableFuture<Void>();
        var completionCount = new AtomicInteger();
        var client = new ScriptedModelClient(
                request -> assistant("first done"),
                request -> {
                    assertEquals("second prompt", lastUserText(request));
                    return assistant("second turn done");
                },
                request -> {
                    assertEquals("late follow-up", lastUserText(request));
                    return assistant("all done");
                });
        try (var session = new CodingAgentSession(new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                null, null, null, null, null, null,
                event -> {
                    if (event instanceof CodingAgentEvent.RunCompleted
                            && completionCount.incrementAndGet() == 1) {
                        completionReached.countDown();
                        return completionGate;
                    }
                    return CompletableFuture.completedStage(null);
                }, null))) {
            var first = session.prompt("first prompt");
            assertTrue(completionReached.await(5, TimeUnit.SECONDS));
            assertTrue(session.isRunning());
            session.followUp("late follow-up");
            completionGate.complete(null);
            first.toCompletableFuture().join();

            var second = session.prompt("second prompt").toCompletableFuture().join();
            assertEquals("all done", text(second.finalMessage()));
            assertEquals(3, client.requests().size());
        }
    }

    @Test
    void eventSinkBackpressureAndFailureRemainInfrastructureSemantics() {
        var gate = new CompletableFuture<Void>();
        var client = new ScriptedModelClient(request -> assistant("ok"));
        var sequence = new AtomicInteger();
        try (var session = new CodingAgentSession(new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                null, null, null, null, null, null,
                event -> sequence.incrementAndGet() == 1
                        ? gate
                        : CompletableFuture.completedStage(null),
                null))) {
            var result = session.prompt("wait");
            assertTrue(client.requests().isEmpty());
            assertFalse(result.toCompletableFuture().isDone());
            gate.complete(null);
            assertEquals("ok", text(result.toCompletableFuture().join().finalMessage()));
        }

        var rejectedClient = new ScriptedModelClient(request -> assistant("unused"));
        try (var session = new CodingAgentSession(new CodingAgentConfig(
                directory, MODEL, rejectedClient, new ObjectMapper(),
                null, null, null, null, null, null,
                event -> CompletableFuture.failedStage(new IllegalStateException("sink failed")),
                null))) {
            var failure = assertThrows(CompletionException.class,
                    () -> session.prompt("fail").toCompletableFuture().join());
            assertEquals("sink failed", failure.getCause().getMessage());
            assertFalse(session.isRunning());
            assertTrue(rejectedClient.requests().isEmpty());
        }
    }

    @Test
    void abortProducesAbortedProductResult() throws Exception {
        var client = new CancellationAwareModelClient();
        try (var session = new CodingAgentSession(new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                null, null, null, null, null, null, null, null))) {
            var result = session.prompt("abort me");
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            session.followUp("after abort");
            session.abort();

            assertTrue(result.toCompletableFuture().join().aborted());
            assertEquals("done", text(session.prompt("next prompt").toCompletableFuture()
                    .join().finalMessage()));
            assertEquals(3, client.requests.size());
            assertEquals("after abort", lastUserText(client.requests.get(2)));
            assertFalse(session.isRunning());
        }
    }

    @Test
    void terminalModelFailureRemainsAProductResultAndPreservesPendingFollowUp() throws Exception {
        var client = new FailureControlledModelClient();
        try (var session = new CodingAgentSession(new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                null, null, null, null, null, null, null, null))) {
            var failedRun = session.prompt("fail normally");
            assertTrue(client.started.await(5, TimeUnit.SECONDS));
            session.followUp("after failure");
            client.fail();

            var failureResult = failedRun.toCompletableFuture().join();
            assertEquals(StopReason.ERROR, failureResult.finalMessage().stopReason());
            assertEquals("model failed", session.state().errorMessage());
            assertEquals("done", text(session.prompt("next prompt").toCompletableFuture()
                    .join().finalMessage()));
            assertEquals(3, client.requests.size());
            assertEquals("after failure", lastUserText(client.requests.get(2)));
        }
    }

    @Test
    void closeCancelsActiveRunWithoutHoldingTheLifecycleLock() throws Exception {
        var client = new CancellationAwareModelClient();
        var session = new CodingAgentSession(new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                null, null, null, null, null, null, null, null));
        var result = session.prompt("close me");
        assertTrue(client.started.await(5, TimeUnit.SECONDS));

        session.close();

        assertTrue(result.toCompletableFuture().join().aborted());
        assertFalse(session.isRunning());
        assertThrows(IllegalStateException.class, () -> session.prompt("closed"));
    }

    @Test
    void closesIdempotentlyAndRejectsNewMessages() {
        var client = new ScriptedModelClient(request -> assistant("ok"));
        var session = new CodingAgentSession(new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                null, null, null, null, null, null, null, null));
        session.close();
        session.close();

        assertThrows(IllegalStateException.class, () -> session.prompt("no"));
        assertThrows(IllegalStateException.class, () -> session.followUp("no"));
        session.abort();
    }

    @Test
    void runtimeEventRejectsAgentCompletedAndSnapshotsMutableArgumentsOnEveryAccess() {
        var original = JsonNodeFactory.instance.objectNode().put("path", "original.txt");
        var event = new CodingAgentEvent.RuntimeEvent(
                new AgentEvent.ToolStarted(new Content.ToolCall("id", "read", original)));
        original.put("path", "runtime-mutated.txt");

        var first = (AgentEvent.ToolStarted) event.event();
        assertEquals("original.txt", first.call().arguments().get("path").textValue());
        ((com.fasterxml.jackson.databind.node.ObjectNode) first.call().arguments())
                .put("path", "observer-mutated.txt");
        var second = (AgentEvent.ToolStarted) event.event();
        assertEquals("original.txt", second.call().arguments().get("path").textValue());

        var state = new CodingAgentState(true, StandardAgentMessage.of(new Message.Assistant(
                List.of(second.call()), StopReason.TOOL_CALL, null, Usage.zero(), Instant.EPOCH, MODEL)),
                java.util.Set.of("id"), null);
        var stateCall = (Content.ToolCall) ((Message.Assistant)
                ((StandardAgentMessage) state.streamingMessage()).message()).content().getFirst();
        ((com.fasterxml.jackson.databind.node.ObjectNode) stateCall.arguments()).put("path", "state-mutated");
        var stateCallAgain = (Content.ToolCall) ((Message.Assistant)
                ((StandardAgentMessage) state.streamingMessage()).message()).content().getFirst();
        assertEquals("original.txt", stateCallAgain.arguments().get("path").textValue());

        var deltaArguments = JsonNodeFactory.instance.objectNode().put("path", "delta.txt");
        var deltaCall = new Content.ToolCall("delta", "read", deltaArguments);
        var partial = new Message.Assistant(
                List.of(deltaCall), StopReason.TOOL_CALL, null, Usage.zero(), Instant.EPOCH, MODEL);
        var updated = new CodingAgentEvent.RuntimeEvent(new AgentEvent.MessageUpdated(
                StandardAgentMessage.of(partial),
                new AssistantMessageEvent.ToolCallEnd(0, deltaCall, partial)));
        deltaArguments.put("path", "runtime-delta-mutation.txt");
        var updatedSnapshot = (AgentEvent.MessageUpdated) updated.event();
        var deltaSnapshot = (AssistantMessageEvent.ToolCallEnd) updatedSnapshot.delta();
        assertEquals("delta.txt", deltaSnapshot.toolCall().arguments().get("path").textValue());
        ((com.fasterxml.jackson.databind.node.ObjectNode) deltaSnapshot.toolCall().arguments())
                .put("path", "observer-delta-mutation.txt");
        var updatedAgain = (AgentEvent.MessageUpdated) updated.event();
        assertEquals("delta.txt", ((AssistantMessageEvent.ToolCallEnd) updatedAgain.delta())
                .toolCall().arguments().get("path").textValue());

        var finalAssistant = assistant("done");
        var context = new AgentContext("prompt", List.of(StandardAgentMessage.of(finalAssistant)), List.of());
        var loopResult = new LoopResult(context, context.messages());
        assertThrows(IllegalArgumentException.class,
                () -> new CodingAgentEvent.RuntimeEvent(new AgentEvent.AgentCompleted(loopResult)));
    }

    @Test
    void streamingSinkFailureFailsProductStageAndAllowsAnotherRun() throws Exception {
        assertStreamingSinkFailure(
                CompletableFuture.failedStage(new IllegalStateException("streaming sink failed")),
                "streaming sink failed");
    }

    @Test
    void nullStreamingSinkStageFailsProductStageAndAllowsAnotherRun() throws Exception {
        assertStreamingSinkFailure(null, "coding event sink returned null stage");
    }

    private void assertStreamingSinkFailure(CompletionStage<Void> rejection, String expectedMessage) throws Exception {
        var events = new CopyOnWriteArrayList<CodingAgentEvent>();
        var rejectOnce = new AtomicBoolean(true);
        ModelClient client = (request, cancellation) -> {
            var stream = new AssistantMessageStream();
            var done = assistant("done");
            stream.push(new AssistantMessageEvent.Start(assistant("")));
            stream.push(new AssistantMessageEvent.TextDelta(0, "done", done));
            stream.push(new AssistantMessageEvent.Done(StopReason.STOP, done));
            return stream;
        };
        var config = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                null, null, null, null, null, null, event -> {
                    events.add(event);
                    if (event instanceof CodingAgentEvent.RuntimeEvent runtime
                            && runtime.event() instanceof AgentEvent.MessageUpdated
                            && rejectOnce.getAndSet(false)) {
                        return rejection;
                    }
                    return CompletableFuture.completedStage(null);
                }, null);

        try (var session = new CodingAgentSession(config)) {
            var run = session.prompt("first").toCompletableFuture();
            var failure = assertThrows(ExecutionException.class, () -> run.get(5, TimeUnit.SECONDS));
            assertEquals(expectedMessage, failure.getCause().getMessage());
            assertFalse(session.isRunning());
            assertNull(session.state().streamingMessage());
            assertNull(session.state().errorMessage());
            assertTrue(session.state().pendingToolCalls().isEmpty());
            assertTrue(events.stream().noneMatch(CodingAgentEvent.RunCompleted.class::isInstance));

            var recovered = session.prompt("retry").toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("done", text(recovered.finalMessage()));
            assertEquals(1, events.stream().filter(CodingAgentEvent.RunCompleted.class::isInstance).count());
        }
    }

    private static Message.Assistant assistant(String text) {
        return new Message.Assistant(List.of(new Content.Text(text)), StopReason.STOP,
                null, Usage.zero(), Instant.EPOCH, MODEL);
    }

    private static String text(Message.Assistant assistant) {
        return assertInstanceOf(Content.Text.class, assistant.content().getFirst()).text();
    }

    private static String lastUserText(ModelRequest request) {
        var user = request.messages().stream()
                .filter(Message.User.class::isInstance)
                .map(Message.User.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();
        return assertInstanceOf(Content.Text.class, user.content().getFirst()).text();
    }

    private static AssistantMessageStream completedStream(Message.Assistant assistant) {
        var stream = new AssistantMessageStream();
        stream.push(new AssistantMessageEvent.Start(new Message.Assistant(
                List.of(), StopReason.STOP, null, Usage.zero(), Instant.EPOCH, MODEL)));
        stream.push(new AssistantMessageEvent.Done(assistant.stopReason(), assistant));
        return stream;
    }

    private static final class BlockingModelClient implements ModelClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AssistantMessageStream firstStream = new AssistantMessageStream();
        private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            requests.add(request);
            if (requests.size() > 1) {
                return completedStream(assistant("done"));
            }
            started.countDown();
            firstStream.push(new AssistantMessageEvent.Start(new Message.Assistant(
                    List.of(), StopReason.STOP, null, Usage.zero(), Instant.EPOCH, MODEL)));
            return firstStream;
        }

        void complete() {
            var assistant = assistant("done");
            firstStream.push(new AssistantMessageEvent.Done(StopReason.STOP, assistant));
        }
    }

    private static final class CancellationAwareModelClient implements ModelClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            requests.add(request);
            if (requests.size() > 1) {
                return completedStream(assistant("done"));
            }
            var stream = new AssistantMessageStream();
            stream.push(new AssistantMessageEvent.Start(new Message.Assistant(
                    List.of(), StopReason.STOP, null, Usage.zero(), Instant.EPOCH, MODEL)));
            cancellation.onCancellation(() -> {
                var aborted = new Message.Assistant(
                        List.of(), StopReason.ABORTED, "aborted", Usage.zero(), Instant.EPOCH, MODEL);
                stream.push(new AssistantMessageEvent.Error(StopReason.ABORTED, aborted));
            });
            started.countDown();
            return stream;
        }
    }

    private static final class FailureControlledModelClient implements ModelClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
        private final AssistantMessageStream firstStream = new AssistantMessageStream();

        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            requests.add(request);
            if (requests.size() > 1) {
                return completedStream(assistant("done"));
            }
            firstStream.push(new AssistantMessageEvent.Start(new Message.Assistant(
                    List.of(), StopReason.STOP, null, Usage.zero(), Instant.EPOCH, MODEL)));
            started.countDown();
            return firstStream;
        }

        void fail() {
            var failure = new Message.Assistant(
                    List.of(), StopReason.ERROR, "model failed", Usage.zero(), Instant.EPOCH, MODEL);
            firstStream.push(new AssistantMessageEvent.Error(StopReason.ERROR, failure));
        }
    }
}
