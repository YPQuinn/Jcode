package site.pplee.jcode.agentcore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.ContextTransformer;
import site.pplee.jcode.agentcore.message.MessageProjector;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.support.ProductMessage;
import site.pplee.jcode.agentcore.support.RecordingEventSink;

import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.ModelRef;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Context projection contract tests: transform-before-project ordering,
 * per-turn repetition, transcript isolation, default filtering, custom
 * projection, async waiting, cancellation, and failure normalization.
 */
class ContextProjectionTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef MODEL = new ModelRef("test", "test-api", "test-model");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private static Message.User user(String text) {
        return new Message.User(List.of(new Content.Text(text)), T1);
    }

    private static AgentMessage userMsg(String text) {
        return StandardAgentMessage.of(user(text));
    }

    private static Message.Assistant assistantText(String text, StopReason reason) {
        return Message.Assistant.of(List.of(new Content.Text(text)), reason, T1);
    }

    private static AgentContext contextWithMessages(AgentMessage... msgs) {
        return new AgentContext("sys", List.of(msgs), List.of());
    }

    private LoopResult runPrompt(
            site.pplee.jcode.agentcore.support.ScriptedModelClient client,
            AgentContext ctx,
            AgentEventSink sink,
            ContextTransformer transformer,
            MessageProjector projector
    ) {
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var cfg = new AgentLoopConfig(
                MODEL, client, MAPPER, transformer, projector,
                null, null, null, null, null, new RunEventEmitter(sink));
        return loop.runPrompt(List.of(userMsg("hi")), ctx, cfg, source.signal());
    }

    private AgentLoopConfig configWith(
            site.pplee.jcode.agentcore.support.ScriptedModelClient client,
            AgentEventSink sink,
            ContextTransformer transformer,
            MessageProjector projector
    ) {
        return new AgentLoopConfig(
                MODEL, client, MAPPER, transformer, projector,
                null, null, null, null, null, new RunEventEmitter(sink));
    }

    // --- ordering ---

    @Test
    void transformerRunsBeforeProjectorBeforeModel() {
        var callOrder = new CopyOnWriteArrayList<String>();
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("ok", StopReason.STOP));
        var transformer = (ContextTransformer) (messages, cancellation) -> {
            callOrder.add("transform");
            return CompletableFuture.completedFuture(messages);
        };
        var projector = (MessageProjector) messages -> {
            callOrder.add("project");
            return MessageProjector.standard().project(messages);
        };
        runPrompt(client, contextWithMessages(), new RecordingEventSink(), transformer, projector);

        assertEquals(List.of("transform", "project"), callOrder);
        assertEquals(1, client.receivedRequests().size(), "model must be called after both seams");
    }

    // --- default behavior ---

    @Test
    void defaultProjectorFiltersCustomMessages() {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("ok", StopReason.STOP));
        runPrompt(client, contextWithMessages(userMsg("standard"), new ProductMessage("custom")),
                new RecordingEventSink(), ContextTransformer.identity(), MessageProjector.standard());

        var req = client.receivedRequests().get(0);
        assertEquals(2, req.messages().size());
        assertInstanceOf(Message.User.class, req.messages().get(0));
        assertInstanceOf(Message.User.class, req.messages().get(1));
    }

    @Test
    void defaultConfigUsesIdentityAndStandard() {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("ok", StopReason.STOP));
        runPrompt(client, contextWithMessages(userMsg("hi")),
                new RecordingEventSink(), null, null);

        var req = client.receivedRequests().get(0);
        assertEquals(2, req.messages().size());
    }

    // --- custom projection ---

    @Test
    void customProjectorProjectsProductMessage() {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("ok", StopReason.STOP));
        var projector = (MessageProjector) messages -> messages.stream()
                .flatMap(m -> {
                    if (m instanceof StandardAgentMessage sam) {
                        return Stream.of(sam.message());
                    }
                    if (m instanceof ProductMessage pm) {
                        return Stream.of((Message) user(pm.text()));
                    }
                    return Stream.empty();
                })
                .toList();
        runPrompt(client, contextWithMessages(new ProductMessage("custom"), userMsg("standard")),
                new RecordingEventSink(), ContextTransformer.identity(), projector);

        var req = client.receivedRequests().get(0);
        assertEquals(3, req.messages().size());
        assertInstanceOf(Message.User.class, req.messages().get(0));
        assertEquals("custom", ((Content.Text) ((Message.User) req.messages().get(0)).content().get(0)).text());
        assertInstanceOf(Message.User.class, req.messages().get(1));
        assertEquals("standard", ((Content.Text) ((Message.User) req.messages().get(1)).content().get(0)).text());
    }

    // --- per-turn repetition ---

    @Test
    void projectionRunsBeforeEveryModelCall() {
        var transformCount = new AtomicInteger(0);
        var projectCount = new AtomicInteger(0);
        var tc = new Content.ToolCall("c1", "echo", MAPPER.getNodeFactory().textNode("hi"));
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                new Message.Assistant(List.of(tc), StopReason.TOOL_CALL, null,
                        site.pplee.jcode.ai.message.Usage.zero(), T1),
                assistantText("done", StopReason.STOP));
        var transformer = (ContextTransformer) (messages, cancellation) -> {
            transformCount.incrementAndGet();
            return CompletableFuture.completedFuture(messages);
        };
        var projector = (MessageProjector) messages -> {
            projectCount.incrementAndGet();
            return MessageProjector.standard().project(messages);
        };
        var ctx = new AgentContext("sys", List.of(),
                List.of(site.pplee.jcode.agentcore.support.TestTools.echo()));

        runPrompt(client, ctx, new RecordingEventSink(), transformer, projector);

        assertEquals(2, transformCount.get(), "transformer must run before each model call");
        assertEquals(2, projectCount.get(), "projector must run before each model call");
        assertEquals(2, client.receivedRequests().size());
    }

    @Test
    void secondProjectionSeesToolResults() {
        var transformerInputs = new CopyOnWriteArrayList<List<AgentMessage>>();
        var tc = new Content.ToolCall("c1", "echo", MAPPER.getNodeFactory().textNode("hi"));
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                new Message.Assistant(List.of(tc), StopReason.TOOL_CALL, null,
                        site.pplee.jcode.ai.message.Usage.zero(), T1),
                assistantText("done", StopReason.STOP));
        var transformer = (ContextTransformer) (messages, cancellation) -> {
            transformerInputs.add(List.copyOf(messages));
            return CompletableFuture.completedFuture(messages);
        };
        var ctx = new AgentContext("sys", List.of(),
                List.of(site.pplee.jcode.agentcore.support.TestTools.echo()));

        runPrompt(client, ctx, new RecordingEventSink(), transformer, MessageProjector.standard());

        var secondInput = transformerInputs.get(1);
        assertTrue(secondInput.stream().anyMatch(m ->
                m instanceof StandardAgentMessage sam && sam.message() instanceof Message.Assistant));
        assertTrue(secondInput.stream().anyMatch(m ->
                m instanceof StandardAgentMessage sam && sam.message() instanceof Message.ToolResultMessage));
    }

    // --- transcript isolation ---

    @Test
    void transformedViewDoesNotReplaceTranscript() {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("ok", StopReason.STOP));
        var oldUser = userMsg("old-user");
        var oldAssistant = StandardAgentMessage.of(assistantText("old-assistant", StopReason.STOP));
        var transformer = (ContextTransformer) (messages, cancellation) ->
                CompletableFuture.completedFuture(List.of(messages.get(messages.size() - 1)));

        var result = runPrompt(client, contextWithMessages(oldUser, oldAssistant),
                new RecordingEventSink(), transformer, MessageProjector.standard());

        var req = client.receivedRequests().get(0);
        assertEquals(1, req.messages().size(), "request only got the last message");

        var transcript = result.context().messages();
        assertEquals(4, transcript.size());
        assertEquals(oldUser, transcript.get(0));
        assertEquals(oldAssistant, transcript.get(1));
    }

    @Test
    void injectedMessagesAreRequestOnly() {
        var recorder = new RecordingEventSink();
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("ok", StopReason.STOP));
        var injected = userMsg("injected");
        var transformer = (ContextTransformer) (messages, cancellation) -> {
            var combined = new ArrayList<AgentMessage>(messages);
            combined.add(injected);
            return CompletableFuture.completedFuture(List.copyOf(combined));
        };

        var result = runPrompt(client, contextWithMessages(userMsg("hi")),
                recorder, transformer, MessageProjector.standard());

        var req = client.receivedRequests().get(0);
        assertEquals(3, req.messages().size());
        assertEquals("injected", ((Content.Text) ((Message.User) req.messages().get(2)).content().get(0)).text());

        var transcript = result.context().messages();
        assertEquals(3, transcript.size());
        assertFalse(transcript.contains(injected));
        assertFalse(result.newMessages().contains(injected));
        assertFalse(recorder.events().stream().anyMatch(e ->
                e instanceof AgentEvent.MessageStarted ms && ms.message().equals(injected)));
        assertFalse(recorder.events().stream().anyMatch(e ->
                e instanceof AgentEvent.MessageCompleted mc && mc.message().equals(injected)));
    }

    // --- async waiting ---

    @Test
    void transformerStageIsAwaited() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var projectRan = new AtomicInteger(0);
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("ok", StopReason.STOP));
        var transformer = (ContextTransformer) (messages, cancellation) -> {
            started.countDown();
            var future = new CompletableFuture<List<AgentMessage>>();
            CompletableFuture.runAsync(() -> {
                try { release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                future.complete(messages);
            });
            return future;
        };
        var projector = (MessageProjector) messages -> {
            projectRan.set(1);
            return MessageProjector.standard().project(messages);
        };

        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var cfg = configWith(client, new RecordingEventSink(), transformer, projector);
        var fut = CompletableFuture.supplyAsync(
                () -> loop.runPrompt(List.of(userMsg("hi")), contextWithMessages(), cfg, source.signal()),
                executor);

        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals(0, projectRan.get(), "projector must not run before transformer stage completes");
        release.countDown();
        fut.get(3, TimeUnit.SECONDS);
        assertEquals(1, projectRan.get());
    }

    // --- failure normalization ---

    @Test
    void transformerFailureEmitsCompleteLifecycle() {
        var recorder = new RecordingEventSink();
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("never", StopReason.STOP));
        var transformer = (ContextTransformer) (messages, cancellation) ->
                CompletableFuture.failedFuture(new RuntimeException("boom"));

        var result = runPrompt(client, contextWithMessages(), recorder, transformer, MessageProjector.standard());

        assertEquals(0, client.receivedRequests().size(), "model must not be called");

        var transcript = result.context().messages();
        assertEquals(2, transcript.size());
        var last = transcript.get(1);
        var assistant = ((StandardAgentMessage) last).message();
        assertEquals(StopReason.ERROR, ((Message.Assistant) assistant).stopReason());

        var events = recorder.events();
        int msgStarted = 0, msgCompleted = 0;
        for (var e : events) {
            if (e instanceof AgentEvent.MessageStarted) msgStarted++;
            if (e instanceof AgentEvent.MessageCompleted) msgCompleted++;
        }
        assertTrue(msgStarted >= msgCompleted, "every MessageCompleted must have a preceding MessageStarted");
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.TurnCompleted));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.AgentCompleted));
    }

    @Test
    void synchronousTransformerFailureIsNormalized() {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("never", StopReason.STOP));
        var transformer = (ContextTransformer) (messages, cancellation) -> {
            throw new RuntimeException("sync boom");
        };

        var result = runPrompt(client, contextWithMessages(), new RecordingEventSink(),
                transformer, MessageProjector.standard());

        assertEquals(0, client.receivedRequests().size());
        var last = result.context().messages().get(1);
        var assistant = ((StandardAgentMessage) last).message();
        assertEquals(StopReason.ERROR, ((Message.Assistant) assistant).stopReason());
    }

    @Test
    void projectorFailureEmitsCompleteLifecycle() {
        var recorder = new RecordingEventSink();
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("never", StopReason.STOP));
        var projector = (MessageProjector) messages -> {
            throw new RuntimeException("projector boom");
        };

        var result = runPrompt(client, contextWithMessages(), recorder,
                ContextTransformer.identity(), projector);

        assertEquals(0, client.receivedRequests().size());
        var last = result.context().messages().get(1);
        var assistant = ((StandardAgentMessage) last).message();
        assertEquals(StopReason.ERROR, ((Message.Assistant) assistant).stopReason());
        assertTrue(((Message.Assistant) assistant).errorMessage().contains("projector"));
    }

    @Test
    void transformerErrorMessagePrefix() {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("never", StopReason.STOP));
        var transformer = (ContextTransformer) (messages, cancellation) ->
                CompletableFuture.failedFuture(new RuntimeException("boom"));

        var result = runPrompt(client, contextWithMessages(), new RecordingEventSink(),
                transformer, MessageProjector.standard());

        var last = result.context().messages().get(1);
        var assistant = ((StandardAgentMessage) last).message();
        assertTrue(((Message.Assistant) assistant).errorMessage().contains("context transformer failed"));
    }

    @Test
    void invalidTransformerOutputIsNormalized() {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("never", StopReason.STOP));
        var transformer = (ContextTransformer) (messages, cancellation) ->
                CompletableFuture.completedFuture(null);

        var result = runPrompt(client, contextWithMessages(), new RecordingEventSink(),
                transformer, MessageProjector.standard());

        assertEquals(0, client.receivedRequests().size());
        var last = result.context().messages().get(1);
        var assistant = ((StandardAgentMessage) last).message();
        assertEquals(StopReason.ERROR, ((Message.Assistant) assistant).stopReason());
    }

    @Test
    void invalidProjectorOutputIsNormalized() {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("never", StopReason.STOP));
        var projector = (MessageProjector) messages -> null;

        var result = runPrompt(client, contextWithMessages(), new RecordingEventSink(),
                ContextTransformer.identity(), projector);

        assertEquals(0, client.receivedRequests().size());
        var last = result.context().messages().get(1);
        var assistant = ((StandardAgentMessage) last).message();
        assertEquals(StopReason.ERROR, ((Message.Assistant) assistant).stopReason());
    }

    // --- cancellation ---

    @Test
    void preCancelledRunSkipsProjection() {
        var transformCount = new AtomicInteger(0);
        var projectCount = new AtomicInteger(0);
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("never", StopReason.STOP));
        var transformer = (ContextTransformer) (messages, cancellation) -> {
            transformCount.incrementAndGet();
            return CompletableFuture.completedFuture(messages);
        };
        var projector = (MessageProjector) messages -> {
            projectCount.incrementAndGet();
            return MessageProjector.standard().project(messages);
        };

        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        source.cancel();
        var cfg = configWith(client, new RecordingEventSink(), transformer, projector);
        var result = loop.runPrompt(List.of(userMsg("hi")), contextWithMessages(), cfg, source.signal());

        assertEquals(0, transformCount.get());
        assertEquals(0, projectCount.get());
        assertEquals(0, client.receivedRequests().size());
        var last = result.context().messages().get(1);
        var assistant = ((StandardAgentMessage) last).message();
        assertEquals(StopReason.ABORTED, ((Message.Assistant) assistant).stopReason());
    }

    @Test
    void abortDuringTransformProducesAbortedAssistant() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var projectCount = new AtomicInteger(0);
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("never", StopReason.STOP));
        var transformer = (ContextTransformer) (messages, cancellation) -> {
            started.countDown();
            var future = new CompletableFuture<List<AgentMessage>>();
            CompletableFuture.runAsync(() -> {
                try { release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                future.complete(messages);
            });
            return future;
        };
        var projector = (MessageProjector) messages -> {
            projectCount.incrementAndGet();
            return MessageProjector.standard().project(messages);
        };

        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var cfg = configWith(client, new RecordingEventSink(), transformer, projector);
        var fut = CompletableFuture.supplyAsync(
                () -> loop.runPrompt(List.of(userMsg("hi")), contextWithMessages(), cfg, source.signal()),
                executor);

        assertTrue(started.await(2, TimeUnit.SECONDS));
        source.cancel();
        release.countDown();
        var result = fut.get(3, TimeUnit.SECONDS);

        assertEquals(0, projectCount.get());
        assertEquals(0, client.receivedRequests().size());
        var last = result.context().messages().get(1);
        var assistant = ((StandardAgentMessage) last).message();
        assertEquals(StopReason.ABORTED, ((Message.Assistant) assistant).stopReason());
    }

    @Test
    void transformerReceivesRunCancellationSignal() throws Exception {
        var seenSignal = new java.util.concurrent.atomic.AtomicReference<CancellationSignal>();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("never", StopReason.STOP));
        var transformer = (ContextTransformer) (messages, cancellation) -> {
            seenSignal.set(cancellation);
            started.countDown();
            var future = new CompletableFuture<List<AgentMessage>>();
            CompletableFuture.runAsync(() -> {
                try { release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                future.complete(messages);
            });
            return future;
        };

        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var cfg = configWith(client, new RecordingEventSink(), transformer, MessageProjector.standard());
        var fut = CompletableFuture.supplyAsync(
                () -> loop.runPrompt(List.of(userMsg("hi")), contextWithMessages(), cfg, source.signal()),
                executor);

        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertNotNull(seenSignal.get());
        assertFalse(seenSignal.get().isCancelled());
        source.cancel();
        release.countDown();
        fut.get(3, TimeUnit.SECONDS);
    }

    // --- Agent-level failure propagation ---

    @Test
    void transformerFailureDoesNotFailAgentFuture() throws Exception {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("never", StopReason.STOP));
        var transformer = (ContextTransformer) (messages, cancellation) ->
                CompletableFuture.failedFuture(new RuntimeException("boom"));
        var cfg = new AgentConfig(
                new AgentContext("sys", List.of(), List.of()),
                MODEL, client, MAPPER, transformer, MessageProjector.standard(),
                null, null, null, null, null, null);
        try (var agent = new Agent(cfg)) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertFalse(agent.isRunning());
            assertNotNull(result);
        }
    }

    @Test
    void agentReusableAfterProjectionFailure() throws Exception {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("never", StopReason.STOP),
                assistantText("ok", StopReason.STOP));
        var failThenPass = new AtomicInteger(0);
        var transformer = (ContextTransformer) (messages, cancellation) -> {
            if (failThenPass.getAndIncrement() == 0) {
                return CompletableFuture.failedFuture(new RuntimeException("first fails"));
            }
            return CompletableFuture.completedFuture(messages);
        };
        var cfg = new AgentConfig(
                new AgentContext("sys", List.of(), List.of()),
                MODEL, client, MAPPER, transformer, MessageProjector.standard(),
                null, null, null, null, null, null);
        try (var agent = new Agent(cfg)) {
            agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);
            var result = agent.prompt(user("again")).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertFalse(agent.isRunning());
            assertTrue(result.context().messages().size() > 0);
        }
    }
}
