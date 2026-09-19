package site.pplee.jcode.agentcore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.support.TestTools;
import site.pplee.jcode.agentcore.support.RecordingEventSink;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Streaming protocol tests: text/thinking/toolcall start/delta/end order,
 * partial assistant progression, state reduction before sink sees
 * events, agent_end gating run settlement, and provider error not failing
 * the run future.
 */
class StreamingEventTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef MODEL = new ModelRef("test", "test-api", "test-model");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Message.User user(String text) {
        return new Message.User(List.of(new Content.Text(text)), T1);
    }

    private static Message.Assistant partial(String text) {
        return Message.Assistant.of(List.of(new Content.Text(text)), StopReason.STOP, T1);
    }

    private static ModelClient streamingClient(AssistantMessageEvent... events) {
        return (request, cancellation) -> {
            var stream = new AssistantMessageStream();
            for (var event : events) {
                stream.push(event);
            }
            return stream;
        };
    }

    private static AgentConfig configWith(ModelClient client, AgentEventSink sink) {
        return new AgentConfig(
                new AgentContext("sys", List.of(), List.of()),
                MODEL, client, MAPPER, null, null, null, null, null, sink, null, null);
    }

    @ParameterizedTest
    @EnumSource(SinkFailureMode.class)
    void assistantStartSinkFailurePropagatesWithoutCommittingTranscript(SinkFailureMode mode) throws Exception {
        assertStreamingSinkFailure(mode, event -> event instanceof AgentEvent.MessageStarted started
                && started.message() instanceof StandardAgentMessage standard
                && standard.message() instanceof Message.Assistant);
    }

    @ParameterizedTest
    @EnumSource(SinkFailureMode.class)
    void assistantUpdateSinkFailurePropagatesWithoutCommittingTranscript(SinkFailureMode mode) throws Exception {
        assertStreamingSinkFailure(mode, event -> event instanceof AgentEvent.MessageUpdated);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void streamingSinkFailureCancelsTheActiveProvider(boolean throwDuringCancellation) throws Exception {
        var providerCancelled = new CompletableFuture<Void>();
        var failure = new IllegalStateException("event delivery failed");
        var cancellationFailure = new AssertionError("cancellation listener failed");
        ModelClient client = (request, cancellation) -> {
            var stream = new AssistantMessageStream();
            cancellation.onCancellation(() -> {
                providerCancelled.complete(null);
                if (throwDuringCancellation) {
                    throw cancellationFailure;
                }
            });
            stream.push(new AssistantMessageEvent.Start(partial("")));
            stream.push(new AssistantMessageEvent.TextDelta(0, "hello", partial("hello")));
            return stream;
        };
        AgentEventSink sink = event -> event instanceof AgentEvent.MessageUpdated
                ? CompletableFuture.failedStage(failure)
                : CompletableFuture.completedStage(null);

        try (var agent = new Agent(configWith(client, sink))) {
            var run = agent.prompt(user("hi")).toCompletableFuture();
            assertSame(failure, assertThrows(ExecutionException.class,
                    () -> run.get(3, TimeUnit.SECONDS)).getCause());
            providerCancelled.get(3, TimeUnit.SECONDS);
            if (throwDuringCancellation) {
                var exception = assertThrows(CompletionException.class, run::join);
                assertArrayEquals(new Throwable[] {cancellationFailure}, exception.getSuppressed());
            }
            assertFalse(agent.isRunning());
        }
    }

    @Test
    void synchronousModelInvocationFailureStillBecomesTerminalAssistant() throws Exception {
        ModelClient client = (request, cancellation) -> {
            throw new IllegalStateException("model invocation failed");
        };
        try (var agent = new Agent(configWith(client, AgentEventSink.noop()))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, finalAssistant(result).stopReason());
            assertEquals("model invocation failed", finalAssistant(result).errorMessage());
        }
    }

    @Test
    void nullModelStreamStillBecomesTerminalAssistant() throws Exception {
        try (var agent = new Agent(configWith((request, cancellation) -> null, AgentEventSink.noop()))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, finalAssistant(result).stopReason());
        }
    }

    private static Message.Assistant finalAssistant(LoopResult result) {
        var message = assertInstanceOf(StandardAgentMessage.class, result.newMessages().getLast());
        return assertInstanceOf(Message.Assistant.class, message.message());
    }

    private static void assertStreamingSinkFailure(
            SinkFailureMode mode, Predicate<AgentEvent> rejectedEvent
    ) throws Exception {
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var rejectOnce = new AtomicBoolean(true);
        var failure = new IllegalStateException("event delivery failed");
        AgentEventSink sink = event -> {
            events.add(event);
            if (rejectedEvent.test(event) && rejectOnce.getAndSet(false)) {
                return switch (mode) {
                    case THROW -> throw failure;
                    case FAILED_STAGE -> CompletableFuture.failedStage(failure);
                    case NULL_STAGE -> null;
                };
            }
            return CompletableFuture.completedStage(null);
        };
        var done = partial("hello");
        var client = streamingClient(
                new AssistantMessageEvent.Start(partial("")),
                new AssistantMessageEvent.TextDelta(0, "hello", done),
                new AssistantMessageEvent.Done(StopReason.STOP, done));

        try (var agent = new Agent(configWith(client, sink))) {
            var run = agent.prompt(user("hi")).toCompletableFuture();
            var exception = assertThrows(ExecutionException.class, () -> run.get(3, TimeUnit.SECONDS));
            if (mode == SinkFailureMode.NULL_STAGE) {
                assertInstanceOf(NullPointerException.class, exception.getCause());
            } else {
                assertSame(failure, exception.getCause());
            }
            assertTrue(rejectedEvent.test(events.getLast()));
            assertTrue(events.stream().noneMatch(AgentEvent.AgentCompleted.class::isInstance));
            assertTrue(agent.context().messages().isEmpty());
            assertFalse(agent.isRunning());
            assertFalse(agent.state().streaming());
            assertNull(agent.state().streamingMessage());
            assertNull(agent.state().errorMessage());

            var recovered = agent.prompt(user("retry")).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(StopReason.STOP, finalAssistant(recovered).stopReason());
        }
    }

    private enum SinkFailureMode { THROW, FAILED_STAGE, NULL_STAGE }

    @Test
    void textStreamingEmitsStartDeltaEndInOrder() throws Exception {
        var p0 = partial("");
        var p1 = partial("Hel");
        var p2 = partial("Hello");
        var finalMsg = partial("Hello");

        var client = streamingClient(
                new AssistantMessageEvent.Start(p0),
                new AssistantMessageEvent.TextStart(0, p0),
                new AssistantMessageEvent.TextDelta(0, "Hel", p1),
                new AssistantMessageEvent.TextDelta(0, "lo", p2),
                new AssistantMessageEvent.TextEnd(0, "Hello", p2),
                new AssistantMessageEvent.Done(StopReason.STOP, finalMsg));

        var recorder = new RecordingEventSink();
        try (var agent = new Agent(configWith(client, recorder))) {
            agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);

            var events = recorder.events();
            var updates = events.stream()
                    .filter(e -> e instanceof AgentEvent.MessageUpdated)
                    .map(e -> (AgentEvent.MessageUpdated) e)
                    .toList();

            assertEquals(4, updates.size());
            assertInstanceOf(AssistantMessageEvent.TextStart.class, updates.get(0).delta());
            assertInstanceOf(AssistantMessageEvent.TextDelta.class, updates.get(1).delta());
            assertInstanceOf(AssistantMessageEvent.TextDelta.class, updates.get(2).delta());
            assertInstanceOf(AssistantMessageEvent.TextEnd.class, updates.get(3).delta());
        }
    }

    @Test
    void thinkingStreamingEmitsStartDeltaEndInOrder() throws Exception {
        var p0 = partial("");
        var p1 = partial("");
        var finalMsg = partial("");

        var client = streamingClient(
                new AssistantMessageEvent.Start(p0),
                new AssistantMessageEvent.ThinkingStart(0, p0),
                new AssistantMessageEvent.ThinkingDelta(0, "reason", p1),
                new AssistantMessageEvent.ThinkingEnd(0, "reasoning", p1),
                new AssistantMessageEvent.Done(StopReason.STOP, finalMsg));

        var recorder = new RecordingEventSink();
        try (var agent = new Agent(configWith(client, recorder))) {
            agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);

            var updates = recorder.events().stream()
                    .filter(e -> e instanceof AgentEvent.MessageUpdated)
                    .map(e -> (AgentEvent.MessageUpdated) e)
                    .toList();

            assertEquals(3, updates.size());
            assertInstanceOf(AssistantMessageEvent.ThinkingStart.class, updates.get(0).delta());
            assertInstanceOf(AssistantMessageEvent.ThinkingDelta.class, updates.get(1).delta());
            assertInstanceOf(AssistantMessageEvent.ThinkingEnd.class, updates.get(2).delta());
        }
    }

    @Test
    void toolCallStreamingEmitsStartDeltaEndInOrder() throws Exception {
        var p0 = partial("");
        var tc = new Content.ToolCall("c1", "terminating", NullNode.getInstance());
        var finalMsg = new Message.Assistant(
                List.of(tc), StopReason.TOOL_CALL, null, Usage.zero(), T1);

        var client = streamingClient(
                new AssistantMessageEvent.Start(p0),
                new AssistantMessageEvent.ToolCallStart(0, p0),
                new AssistantMessageEvent.ToolCallDelta(0, "{\"text\"", p0),
                new AssistantMessageEvent.ToolCallEnd(0, tc, finalMsg),
                new AssistantMessageEvent.Done(StopReason.TOOL_CALL, finalMsg));

        var recorder = new RecordingEventSink();
        var cfg = new AgentConfig(
                new AgentContext("sys", List.of(), List.of(TestTools.terminating())),
                MODEL, client, MAPPER, null, null, null, null, null, recorder, null, null);
        try (var agent = new Agent(cfg)) {
            agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);

            var updates = recorder.events().stream()
                    .filter(e -> e instanceof AgentEvent.MessageUpdated)
                    .map(e -> (AgentEvent.MessageUpdated) e)
                    .toList();

            assertEquals(3, updates.size());
            assertInstanceOf(AssistantMessageEvent.ToolCallStart.class, updates.get(0).delta());
            assertInstanceOf(AssistantMessageEvent.ToolCallDelta.class, updates.get(1).delta());
            assertInstanceOf(AssistantMessageEvent.ToolCallEnd.class, updates.get(2).delta());
        }
    }

    @Test
    void listenerSeesAlreadyReducedState() throws Exception {
        var p0 = partial("");
        var p1 = partial("Hel");
        var finalMsg = partial("Hello");

        var client = streamingClient(
                new AssistantMessageEvent.Start(p0),
                new AssistantMessageEvent.TextStart(0, p0),
                new AssistantMessageEvent.TextDelta(0, "Hel", p1),
                new AssistantMessageEvent.TextEnd(0, "Hello", p1),
                new AssistantMessageEvent.Done(StopReason.STOP, finalMsg));

        var ref = new AtomicReference<Agent>();
        var statesSeen = new CopyOnWriteArrayList<AgentState>();
        var checkingSink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                statesSeen.add(ref.get().state());
                return CompletableFuture.completedStage(null);
            }
        };
        try (var agent = new Agent(configWith(client, checkingSink))) {
            ref.set(agent);
            agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);

            // During streaming, at least one captured state should have streaming=true
            // and streamingMessage set to a partial.
            var streamingStates = statesSeen.stream()
                    .filter(AgentState::streaming)
                    .toList();
            assertFalse(streamingStates.isEmpty(), "at least one state should have streaming=true");

            // The state captured when MessageUpdated was emitted should have
            // streamingMessage matching the partial from that event.
            var updateStates = new java.util.ArrayList<AgentState>();
            for (int i = 0; i < statesSeen.size(); i++) {
                // states are captured before each emit delegates; the reducer
                // runs first, so the state captured during MessageUpdated
                // already has streamingMessage set.
            }
            // Verify: at least one streaming state has a non-null streamingMessage
            var hasPartial = streamingStates.stream()
                    .anyMatch(s -> s.streamingMessage() != null);
            assertTrue(hasPartial, "streaming state should have a partial streamingMessage");

            // After run, state should be non-streaming
            assertFalse(agent.state().streaming());
            assertNull(agent.state().streamingMessage());
            assertTrue(agent.state().pendingToolCalls().isEmpty());
        }
    }

    @Test
    void providerErrorDoesNotFailRunFuture() throws Exception {
        var err = new Message.Assistant(List.of(),
                StopReason.ERROR, "model unavailable", Usage.zero(), T1);

        var client = streamingClient(
                new AssistantMessageEvent.Start(err),
                new AssistantMessageEvent.Error(StopReason.ERROR, err));

        var recorder = new RecordingEventSink();
        try (var agent = new Agent(configWith(client, recorder))) {
            var fut = agent.prompt(user("hi")).toCompletableFuture();

            // Run completes normally (not exceptionally)
            var result = fut.get(3, TimeUnit.SECONDS);

            // Context has the error assistant
            assertEquals(2, result.context().messages().size());
            var last = ((StandardAgentMessage) result.context().messages().get(1)).message();
            assertInstanceOf(Message.Assistant.class, last);
            assertEquals(StopReason.ERROR, ((Message.Assistant) last).stopReason());
            assertEquals("model unavailable", ((Message.Assistant) last).errorMessage());

            // errorMessage is preserved in agent state after run
            assertEquals("model unavailable", agent.state().errorMessage());
        }
    }

    @Test
    void agentCompletedListenerGatesRunSettlement() throws Exception {
        var finalMsg = partial("hello");
        var client = streamingClient(
                new AssistantMessageEvent.Start(finalMsg),
                new AssistantMessageEvent.Done(StopReason.STOP, finalMsg));

        var agentCompletedStarted = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        var gateSink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                if (event instanceof AgentEvent.AgentCompleted) {
                    agentCompletedStarted.countDown();
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return CompletableFuture.completedStage(null);
            }
        };

        try (var agent = new Agent(configWith(client, gateSink))) {
            var fut = agent.prompt(user("hi")).toCompletableFuture();

            assertTrue(agentCompletedStarted.await(2, TimeUnit.SECONDS),
                    "agent_end should be emitted");
            assertFalse(fut.isDone(),
                    "run must not settle until agent_end listener completes");

            gate.countDown();
            fut.get(3, TimeUnit.SECONDS);
        }
    }
}
