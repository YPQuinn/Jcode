package site.pplee.jcode.agentcore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.queue.PendingMessageSource;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.agentcore.support.RecordingEventSink;
import site.pplee.jcode.agentcore.support.ScriptedModelClient;
import site.pplee.jcode.agentcore.support.TestTools;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.agentcore.turn.NextTurnUpdate;
import site.pplee.jcode.agentcore.turn.PrepareNextTurn;
import site.pplee.jcode.agentcore.turn.ShouldStopAfterTurn;
import site.pplee.jcode.agentcore.turn.TurnContext;

import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave 4 next-turn control contract tests: per-request context/model/thinking
 * updates, graceful stop ordering, replacement-context semantics, hook
 * payloads, async waiting, cancellation, and failure normalization.
 */
class NextTurnControlTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef MODEL_A = new ModelRef("test", "test-api", "model-a");
    private static final ModelRef MODEL_B = new ModelRef("test", "test-api", "model-b");
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

    // --- helpers ---

    private static Message.User user(String text) {
        return new Message.User(List.of(new Content.Text(text)), T1);
    }

    private static AgentMessage userMsg(String text) {
        return StandardAgentMessage.of(user(text));
    }

    private static Message.Assistant assistantText(String text, StopReason reason) {
        return Message.Assistant.of(List.of(new Content.Text(text)), reason, T1);
    }

    private static Message.Assistant assistantWithToolCalls(List<Content.ToolCall> calls, StopReason reason) {
        return Message.Assistant.of(new java.util.ArrayList<Content>(calls), reason, T1);
    }

    private static Content.ToolCall toolCall(String id, String name) {
        return new Content.ToolCall(id, name, MAPPER.getNodeFactory().textNode("hi"));
    }

    private static AgentContext contextWithTools(AgentTool<?>... tools) {
        return new AgentContext("sys", List.of(), List.of(tools));
    }

    private static AgentConfig agentConfig(
            AgentContext ctx, ScriptedModelClient client, AgentEventSink sink,
            ThinkingLevel thinking, PrepareNextTurn prepare, ShouldStopAfterTurn stop) {
        return new AgentConfig(ctx, MODEL_A, client, MAPPER, null, null, null, null, null, sink, null, null,
                thinking, prepare, stop);
    }

    private static AgentConfig agentConfig(
            ScriptedModelClient client, AgentEventSink sink,
            ThinkingLevel thinking, PrepareNextTurn prepare, ShouldStopAfterTurn stop) {
        return agentConfig(new AgentContext("sys", List.of(), List.of()), client, sink, thinking, prepare, stop);
    }

    /** The terminal message appended by the failure/abort path. */
    private static Message.Assistant lastAssistant(LoopResult result) {
        var messages = result.context().messages();
        return (Message.Assistant) ((StandardAgentMessage) messages.get(messages.size() - 1)).message();
    }

    /**
     * Assert the synthetic lifecycle of a post-turn hook failure: the normal
     * turn's TurnCompleted, then a failure turn opened with TurnStarted whose
     * message lifecycle carries the same terminal assistant that the terminal
     * TurnCompleted reports.
     */
    private static void assertSyntheticFailureLifecycle(List<AgentEvent> events, String expectedPrefix) {
        assertEquals(1, events.stream().filter(e -> e instanceof AgentEvent.AgentCompleted).count(),
                "exactly one AgentCompleted");
        assertTrue(events.size() >= 6, "expected the normal TurnCompleted plus a synthetic failure turn");
        int n = events.size();
        assertInstanceOf(AgentEvent.TurnCompleted.class, events.get(n - 6),
                "the normal turn must complete before the failure turn");
        assertInstanceOf(AgentEvent.TurnStarted.class, events.get(n - 5),
                "the synthetic failure turn opens with TurnStarted");
        var started = assertInstanceOf(AgentEvent.MessageStarted.class, events.get(n - 4));
        var completed = assertInstanceOf(AgentEvent.MessageCompleted.class, events.get(n - 3));
        var failedTurn = assertInstanceOf(AgentEvent.TurnCompleted.class, events.get(n - 2));
        assertInstanceOf(AgentEvent.AgentCompleted.class, events.get(n - 1));

        var failure = (Message.Assistant) ((StandardAgentMessage) started.message()).message();
        assertEquals(StopReason.ERROR, failure.stopReason());
        assertTrue(failure.errorMessage().contains(expectedPrefix));
        assertEquals(started.message(), completed.message(), "the failure message lifecycle matches");
        assertEquals(failure, failedTurn.assistant(), "the terminal turn carries the same failure assistant");
        assertTrue(failedTurn.toolResults().isEmpty());
    }

    /**
     * Assert the event tail of an abort observed after a turn completed: the
     * normal TurnCompleted, then a synthetic aborted turn opened with
     * TurnStarted and closed by the terminal TurnCompleted.
     */
    private static void assertSyntheticAbortTail(List<AgentEvent> events) {
        assertTrue(events.size() >= 6, "expected the normal TurnCompleted plus a synthetic aborted turn");
        int n = events.size();
        assertInstanceOf(AgentEvent.TurnCompleted.class, events.get(n - 6),
                "the normal turn must complete before the aborted turn");
        assertInstanceOf(AgentEvent.TurnStarted.class, events.get(n - 5),
                "the synthetic aborted turn opens with TurnStarted");
        var started = assertInstanceOf(AgentEvent.MessageStarted.class, events.get(n - 4));
        var completed = assertInstanceOf(AgentEvent.MessageCompleted.class, events.get(n - 3));
        var abortedTurn = assertInstanceOf(AgentEvent.TurnCompleted.class, events.get(n - 2));
        assertInstanceOf(AgentEvent.AgentCompleted.class, events.get(n - 1));

        assertEquals(started.message(), completed.message(), "the aborted message lifecycle matches");
        var aborted = (Message.Assistant) ((StandardAgentMessage) started.message()).message();
        assertEquals(StopReason.ABORTED, aborted.stopReason());
        assertEquals(aborted, abortedTurn.assistant(), "the terminal turn carries the same aborted assistant");
        assertTrue(abortedTurn.toolResults().isEmpty());
    }

    // --- Slice 2: next-turn update scope ---

    @Test
    void secondRequestUsesPrepareNextTurnModelAndThinking() throws Exception {
        var calls = new AtomicInteger();
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                if (calls.getAndIncrement() == 0) {
                    return CompletableFuture.completedStage(new NextTurnUpdate(
                            Optional.empty(), Optional.of(MODEL_B), Optional.of(ThinkingLevel.HIGH)));
                }
                return CompletableFuture.completedStage(NextTurnUpdate.keep());
            }
        };
        var client = new ScriptedModelClient(
                assistantWithToolCalls(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        try (var agent = new Agent(agentConfig(contextWithTools(TestTools.echo()), client,
                AgentEventSink.noop(), null, prepare, null))) {
            agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);
        }

        var reqs = client.receivedRequests();
        assertEquals(2, reqs.size());
        // the update must not retroactively affect the already-completed request
        assertEquals(MODEL_A, reqs.get(0).model());
        assertEquals(ThinkingLevel.PROVIDER_DEFAULT, reqs.get(0).thinkingLevel());
        assertEquals(MODEL_B, reqs.get(1).model());
        assertEquals(ThinkingLevel.HIGH, reqs.get(1).thinkingLevel());
    }

    @Test
    void replacementContextFullyDrivesSecondRequestAndFinalState() throws Exception {
        var replacement = new AgentContext(
                "replacement-sys", List.of(userMsg("replacement-history")), List.of(TestTools.echo()));
        var first = new AtomicBoolean(true);
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                if (first.getAndSet(false)) {
                    return CompletableFuture.completedStage(new NextTurnUpdate(
                            Optional.of(replacement), Optional.empty(), Optional.empty()));
                }
                return CompletableFuture.completedStage(NextTurnUpdate.keep());
            }
        };
        var client = new ScriptedModelClient(
                assistantWithToolCalls(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        var cfg = agentConfig(new AgentContext("original-sys", List.of(), List.of()),
                client, AgentEventSink.noop(), null, prepare, null);
        try (var agent = new Agent(cfg)) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);

            // the second request is built entirely from the replacement context:
            // system prompt, messages, and tools all differ from the original
            var req2 = client.receivedRequests().get(1);
            assertEquals("replacement-sys", req2.systemPrompt());
            assertEquals(List.of(user("replacement-history")), req2.messages());
            assertEquals(1, req2.tools().size());
            assertEquals("echo", req2.tools().get(0).name());

            // final context = replacement + final assistant
            assertEquals("replacement-sys", result.context().systemPrompt());
            assertEquals(2, result.context().messages().size());
            assertEquals(userMsg("replacement-history"), result.context().messages().get(0));
            assertEquals(StandardAgentMessage.of(assistantText("done", StopReason.STOP)),
                    result.context().messages().get(1));
            // newMessages keeps the full append log of this run
            assertEquals(4, result.newMessages().size());
            // agent context persists the replacement across runs
            assertEquals("replacement-sys", agent.context().systemPrompt());
        }
    }

    @Test
    void contextReplacementPrunesRunMessagesButNewMessagesKeepLog() throws Exception {
        var pruned = new AgentContext("pruned", List.of(userMsg("hi")), List.of());
        var first = new AtomicBoolean(true);
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                if (first.getAndSet(false)) {
                    return CompletableFuture.completedStage(new NextTurnUpdate(
                            Optional.of(pruned), Optional.empty(), Optional.empty()));
                }
                return CompletableFuture.completedStage(NextTurnUpdate.keep());
            }
        };
        var client = new ScriptedModelClient(
                assistantWithToolCalls(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        try (var agent = new Agent(agentConfig(contextWithTools(TestTools.echo()), client,
                AgentEventSink.noop(), null, prepare, null))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);

            // pruned context keeps only [prompt] plus the final assistant
            assertEquals(2, result.context().messages().size());
            assertEquals(userMsg("hi"), result.context().messages().get(0));
            assertEquals(StandardAgentMessage.of(assistantText("done", StopReason.STOP)),
                    result.context().messages().get(1));
            // the append log still contains the pruned assistant and tool result
            assertEquals(4, result.newMessages().size());
            assertEquals(StandardAgentMessage.of(
                    assistantWithToolCalls(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL)),
                    result.newMessages().get(1));
            assertTrue(result.newMessages().stream()
                    .anyMatch(m -> m instanceof StandardAgentMessage sam
                            && sam.message() instanceof Message.ToolResultMessage));
        }
    }

    @Test
    void initialThinkingLevelFromConfigReachesRequests() throws Exception {
        var client = new ScriptedModelClient(assistantText("done", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, AgentEventSink.noop(),
                ThinkingLevel.HIGH, null, null))) {
            agent.prompt(user("hi")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
        assertEquals(ThinkingLevel.HIGH, client.receivedRequests().get(0).thinkingLevel());
    }

    @Test
    void modelSwitchOnlyAffectsCurrentRun() throws Exception {
        var calls = new AtomicInteger();
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                if (calls.getAndIncrement() == 0) {
                    return CompletableFuture.completedStage(new NextTurnUpdate(
                            Optional.empty(), Optional.of(MODEL_B), Optional.empty()));
                }
                return CompletableFuture.completedStage(NextTurnUpdate.keep());
            }
        };
        var client = new ScriptedModelClient(
                assistantWithToolCalls(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL),
                assistantText("done1", StopReason.STOP),
                assistantText("done2", StopReason.STOP));
        var cfg = agentConfig(contextWithTools(TestTools.echo()), client, AgentEventSink.noop(), null, prepare, null);
        try (var agent = new Agent(cfg)) {
            agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);
            agent.prompt(user("again")).toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
        var reqs = client.receivedRequests();
        assertEquals(MODEL_A, reqs.get(0).model(), "run 1, turn 1 uses the config model");
        assertEquals(MODEL_B, reqs.get(1).model(), "run 1, turn 2 uses the updated model");
        assertEquals(MODEL_A, reqs.get(2).model(), "run 2 starts fresh from the config model");
    }

    @Test
    void thinkingLevelThreeStatesAndRunLocalReset() throws Exception {
        var calls = new AtomicInteger();
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                return switch (calls.getAndIncrement()) {
                    case 0 -> CompletableFuture.completedStage(NextTurnUpdate.keep());
                    case 1 -> CompletableFuture.completedStage(new NextTurnUpdate(
                            Optional.empty(), Optional.empty(), Optional.of(ThinkingLevel.PROVIDER_DEFAULT)));
                    default -> CompletableFuture.completedStage(NextTurnUpdate.keep());
                };
            }
        };
        var client = new ScriptedModelClient(
                assistantWithToolCalls(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL),
                assistantWithToolCalls(List.of(toolCall("c2", "echo")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP),
                assistantText("again", StopReason.STOP));
        var cfg = agentConfig(contextWithTools(TestTools.echo()), client, AgentEventSink.noop(),
                ThinkingLevel.HIGH, prepare, null);
        try (var agent = new Agent(cfg)) {
            agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);
            agent.prompt(user("again")).toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
        var reqs = client.receivedRequests();
        assertEquals(ThinkingLevel.HIGH, reqs.get(0).thinkingLevel(), "initial config level");
        assertEquals(ThinkingLevel.HIGH, reqs.get(1).thinkingLevel(), "KEEP preserves the current level");
        assertEquals(ThinkingLevel.PROVIDER_DEFAULT, reqs.get(2).thinkingLevel(),
                "explicit PROVIDER_DEFAULT resets the level");
        assertEquals(ThinkingLevel.HIGH, reqs.get(3).thinkingLevel(),
                "a new independent run restores the config initial");
    }

    // --- Slice 3: replacement context + graceful stop ---

    @Test
    void shouldStopSeesReplacementContext() throws Exception {
        var replacement = new AgentContext("new-sys", List.of(), List.of());
        var seenSystem = new AtomicReference<String>();
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                return CompletableFuture.completedStage(new NextTurnUpdate(
                        Optional.of(replacement), Optional.empty(), Optional.empty()));
            }
        };
        var stop = new ShouldStopAfterTurn() {
            @Override
            public CompletionStage<Decision> shouldStopAfterTurn(TurnContext turn, CancellationSignal cancellation) {
                seenSystem.set(turn.context().systemPrompt());
                return CompletableFuture.completedStage(Decision.STOP);
            }
        };
        var client = new ScriptedModelClient(assistantText("done", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, AgentEventSink.noop(), null, prepare, stop))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals("new-sys", seenSystem.get());
            assertEquals("new-sys", result.context().systemPrompt());
            assertEquals(1, client.receivedRequests().size(), "STOP prevents any second model call");
        }
    }

    @Test
    void stopKeepsAssistantStopReasonAndEndsRun() throws Exception {
        var stop = new ShouldStopAfterTurn() {
            @Override
            public CompletionStage<Decision> shouldStopAfterTurn(TurnContext turn, CancellationSignal cancellation) {
                return CompletableFuture.completedStage(Decision.STOP);
            }
        };
        var recorder = new RecordingEventSink();
        var client = new ScriptedModelClient(
                assistantWithToolCalls(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL),
                assistantText("never", StopReason.STOP));
        try (var agent = new Agent(agentConfig(contextWithTools(TestTools.echo()), client, recorder, null, null, stop))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(1, client.receivedRequests().size());
            var assistant = (Message.Assistant) ((StandardAgentMessage) result.context().messages().get(1)).message();
            assertEquals(StopReason.TOOL_CALL, assistant.stopReason(), "STOP must not alter the stop reason");
            assertEquals(3, result.context().messages().size());
            assertEquals(1, recorder.events().stream()
                    .filter(e -> e instanceof AgentEvent.AgentCompleted).count());
        }
    }

    @Test
    void continueDoesNotForceAnotherModelCall() throws Exception {
        var client = new ScriptedModelClient(assistantText("done", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, AgentEventSink.noop(), null, null, null))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(1, client.receivedRequests().size());
            assertEquals(2, result.context().messages().size());
        }
    }

    @Test
    void defaultHooksPreserveWave3Behavior() throws Exception {
        // legacy 12-arg constructor: no next-turn controls configured
        var recorder = new RecordingEventSink();
        var client = new ScriptedModelClient(
                assistantWithToolCalls(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        var cfg = new AgentConfig(
                contextWithTools(TestTools.echo()),
                MODEL_A, client, MAPPER, null, null, null, null, null, recorder, null, null);
        try (var agent = new Agent(cfg)) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(2, client.receivedRequests().size());
            assertEquals(4, result.context().messages().size());
            assertEquals(MODEL_A, client.receivedRequests().get(0).model());
            assertEquals(ThinkingLevel.PROVIDER_DEFAULT, client.receivedRequests().get(0).thinkingLevel());
        }
    }

    @Test
    void continueRunHonorsNextTurnHooks() throws Exception {
        var calls = new AtomicInteger();
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                if (calls.getAndIncrement() == 0) {
                    return CompletableFuture.completedStage(new NextTurnUpdate(
                            Optional.empty(), Optional.of(MODEL_B), Optional.of(ThinkingLevel.HIGH)));
                }
                return CompletableFuture.completedStage(NextTurnUpdate.keep());
            }
        };
        var client = new ScriptedModelClient(
                assistantWithToolCalls(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        var cfg = new AgentConfig(
                new AgentContext("sys", List.of(userMsg("existing")), List.of(TestTools.echo())),
                MODEL_A, client, MAPPER, null, null, null, null, null, AgentEventSink.noop(),
                null, null, null, prepare, null);
        try (var agent = new Agent(cfg)) {
            var result = agent.continueRun().toCompletableFuture().get(3, TimeUnit.SECONDS);
            var reqs = client.receivedRequests();
            assertEquals(2, reqs.size());
            assertEquals(MODEL_A, reqs.get(0).model());
            assertEquals(ThinkingLevel.PROVIDER_DEFAULT, reqs.get(0).thinkingLevel());
            assertEquals(MODEL_B, reqs.get(1).model());
            assertEquals(ThinkingLevel.HIGH, reqs.get(1).thinkingLevel());
            // existing user + assistant(toolcall) + tool result + assistant(done)
            assertEquals(4, result.context().messages().size());
            assertEquals(userMsg("existing"), result.context().messages().get(0));
        }
    }

    // --- Slice 4: stop-before-drain ordering (low-level drain timing) ---

    /** Test-only source that counts drain calls and returns a fixed snapshot. */
    private static final class CountingSource implements PendingMessageSource {
        private final List<AgentMessage> messages;
        private int drains;

        CountingSource(List<AgentMessage> messages) {
            this.messages = messages;
        }

        int drains() {
            return drains;
        }

        @Override
        public List<AgentMessage> drain() {
            drains++;
            return messages;
        }
    }

    @Test
    void stopPreventsPostTurnSteeringAndFollowUpDrain() {
        var steering = new CountingSource(List.of());
        var followUp = new CountingSource(List.of());
        var client = new ScriptedModelClient(assistantText("done", StopReason.STOP));
        var stop = new ShouldStopAfterTurn() {
            @Override
            public CompletionStage<Decision> shouldStopAfterTurn(TurnContext turn, CancellationSignal cancellation) {
                return CompletableFuture.completedStage(Decision.STOP);
            }
        };
        var cfg = new AgentLoopConfig(MODEL_A, client, MAPPER, null, null, null, null, null,
                steering, followUp, new RunEventEmitter(new RecordingEventSink()), null, null, stop);
        var loop = new AgentLoop(executor);
        loop.runPrompt(List.of(userMsg("hi")), new AgentContext("sys", List.of(), List.of()),
                cfg, new CancellationSource().signal());

        assertEquals(1, steering.drains(), "only the initial steering poll runs before STOP");
        assertEquals(0, followUp.drains(), "follow-up is never polled after STOP");
    }

    @Test
    void continueDrainsSteeringAfterTurnAndFollowUpWhenInnerLoopExits() {
        var steering = new CountingSource(List.of());
        var followUp = new CountingSource(List.of());
        var client = new ScriptedModelClient(assistantText("done", StopReason.STOP));
        var cfg = new AgentLoopConfig(MODEL_A, client, MAPPER, null, null, null, null, null,
                steering, followUp, new RunEventEmitter(new RecordingEventSink()), null, null, null);
        var loop = new AgentLoop(executor);
        loop.runPrompt(List.of(userMsg("hi")), new AgentContext("sys", List.of(), List.of()),
                cfg, new CancellationSource().signal());

        assertEquals(2, steering.drains(), "initial poll plus one post-turn poll");
        assertEquals(1, followUp.drains(), "one follow-up poll after the inner loop exits");
    }

    @Test
    void stopLeavesQueuedSteeringForNextRun() throws Exception {
        var ref = new AtomicReference<Agent>();
        var steeringTool = new AgentTool<Object>() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public Class<Object> argumentType() {
                return Object.class;
            }

            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String toolCallId, Object arguments, ToolUpdateSink updates, CancellationSignal cancellation) {
                ref.get().steer(user("steer"));
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("ok"))));
            }
        };
        var stop = new ShouldStopAfterTurn() {
            @Override
            public CompletionStage<Decision> shouldStopAfterTurn(TurnContext turn, CancellationSignal cancellation) {
                return CompletableFuture.completedStage(Decision.STOP);
            }
        };
        var client = new ScriptedModelClient(
                assistantWithToolCalls(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL),
                assistantText("never", StopReason.STOP));
        var cfg = new AgentConfig(
                new AgentContext("sys", List.of(), List.of(steeringTool)),
                MODEL_A, client, MAPPER, null, null, null, null, null, AgentEventSink.noop(),
                null, null, null, null, stop);
        try (var agent = new Agent(cfg)) {
            ref.set(agent);
            agent.steeringMode(QueueMode.ALL);
            agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);

            // STOP after turn 1: no second call, steering stayed queued
            assertEquals(1, client.receivedRequests().size());

            agent.prompt(user("again")).toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
        // the queued steering message is drained by the next run's initial poll
        assertTrue(client.receivedRequests().get(1).messages().stream()
                .filter(m -> m instanceof Message.User)
                .anyMatch(m -> ((Content.Text) ((Message.User) m).content().get(0)).text().equals("steer")));
    }

    // --- Slice 5: payload, ordering, async waiting, cancellation, failures ---

    @Test
    void turnHooksReceiveFullPayload() throws Exception {
        var summaries = new CopyOnWriteArrayList<String>();
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                summaries.add(summarize(turn));
                return CompletableFuture.completedStage(NextTurnUpdate.keep());
            }
        };
        var stop = new ShouldStopAfterTurn() {
            @Override
            public CompletionStage<Decision> shouldStopAfterTurn(TurnContext turn, CancellationSignal cancellation) {
                summaries.add(summarize(turn));
                return CompletableFuture.completedStage(Decision.CONTINUE);
            }
        };
        var client = new ScriptedModelClient(
                assistantWithToolCalls(List.of(toolCall("c1", "echo"), toolCall("c2", "echo")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        try (var agent = new Agent(agentConfig(contextWithTools(TestTools.echo()), client,
                AgentEventSink.noop(), null, prepare, stop))) {
            agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);
        }

        // hooks run after every normal turn: 2 turns x 2 hooks
        assertEquals(4, summaries.size(), "both hooks run once per normal turn");
        assertEquals(List.of(
                "calls=c1,c2;toolResults=c1,c2;context=4;newMessages=4",
                "calls=c1,c2;toolResults=c1,c2;context=4;newMessages=4",
                "calls=;toolResults=;context=5;newMessages=5",
                "calls=;toolResults=;context=5;newMessages=5"), summaries);
    }

    private static String summarize(TurnContext turn) {
        var callIds = turn.assistant().content().stream()
                .filter(Content.ToolCall.class::isInstance)
                .map(c -> ((Content.ToolCall) c).id())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        var resultIds = turn.toolResults().stream()
                .map(Message.ToolResultMessage::toolCallId)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return "calls=" + callIds
                + ";toolResults=" + resultIds
                + ";context=" + turn.context().messages().size()
                + ";newMessages=" + turn.newMessages().size();
    }

    @Test
    void turnCompletedSinkRunsBeforeHooks() throws Exception {
        var counter = new AtomicInteger();
        var turnCompletedOrder = new AtomicInteger(-1);
        var hookOrder = new AtomicInteger(-1);
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                if (event instanceof AgentEvent.TurnCompleted) {
                    turnCompletedOrder.set(counter.incrementAndGet());
                }
                return CompletableFuture.completedStage(null);
            }
        };
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                hookOrder.set(counter.incrementAndGet());
                return CompletableFuture.completedStage(NextTurnUpdate.keep());
            }
        };
        var client = new ScriptedModelClient(assistantText("done", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, sink, null, prepare, null))) {
            agent.prompt(user("hi")).toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
        assertTrue(turnCompletedOrder.get() > 0, "TurnCompleted must have been emitted");
        assertTrue(turnCompletedOrder.get() < hookOrder.get(), "hooks run after TurnCompleted is awaited");
    }

    @Test
    void turnCompletedSinkBlocksHooksUntilSettled() throws Exception {
        var sinkStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var hookCalls = new AtomicInteger();
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                if (event instanceof AgentEvent.TurnCompleted) {
                    sinkStarted.countDown();
                    var future = new CompletableFuture<Void>();
                    CompletableFuture.runAsync(() -> {
                        try {
                            release.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        future.complete(null);
                    });
                    return future;
                }
                return CompletableFuture.completedStage(null);
            }
        };
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                hookCalls.incrementAndGet();
                return CompletableFuture.completedStage(NextTurnUpdate.keep());
            }
        };
        var client = new ScriptedModelClient(assistantText("done", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, sink, null, prepare, null))) {
            var fut = agent.prompt(user("hi"));
            assertTrue(sinkStarted.await(2, TimeUnit.SECONDS));
            assertEquals(0, hookCalls.get(), "hooks must not run before the TurnCompleted sink settles");
            assertFalse(fut.toCompletableFuture().isDone());
            release.countDown();
            fut.toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(1, hookCalls.get());
        }
    }

    @Test
    void turnCompletedSinkFailurePropagatesAndSkipsHooks() throws Exception {
        var hookCalls = new AtomicInteger();
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                if (event instanceof AgentEvent.TurnCompleted) {
                    return CompletableFuture.failedFuture(new RuntimeException("sink boom"));
                }
                return CompletableFuture.completedStage(null);
            }
        };
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                hookCalls.incrementAndGet();
                return CompletableFuture.completedStage(NextTurnUpdate.keep());
            }
        };
        var client = new ScriptedModelClient(assistantText("done", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, sink, null, prepare, null))) {
            var fut = agent.prompt(user("hi")).toCompletableFuture();
            fut.handle((result, error) -> {
                assertNotNull(error, "sink failure must fail the run future exceptionally");
                return null;
            }).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(0, hookCalls.get(), "hooks must not run after a failed sink");
        }
    }

    @Test
    void turnHooksAwaitAsyncStages() throws Exception {
        var hookStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                hookStarted.countDown();
                var future = new CompletableFuture<NextTurnUpdate>();
                CompletableFuture.runAsync(() -> {
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    future.complete(NextTurnUpdate.keep());
                });
                return future;
            }
        };
        var client = new ScriptedModelClient(assistantText("done", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, AgentEventSink.noop(), null, prepare, null))) {
            var fut = agent.prompt(user("hi"));
            assertTrue(hookStarted.await(2, TimeUnit.SECONDS));
            assertFalse(fut.toCompletableFuture().isDone(), "run must wait for the hook stage");
            release.countDown();
            var result = fut.toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(2, result.context().messages().size());
        }
    }

    @Test
    void cancellationPassesToPrepareHookAndAborts() throws Exception {
        var hookStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var seenCancelled = new AtomicBoolean(false);
        var recorder = new RecordingEventSink();
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                hookStarted.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                }
                seenCancelled.set(cancellation.isCancelled());
                return CompletableFuture.completedStage(NextTurnUpdate.keep());
            }
        };
        var client = new ScriptedModelClient(assistantText("first", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, recorder, null, prepare, null))) {
            var fut = agent.prompt(user("hi"));
            assertTrue(hookStarted.await(2, TimeUnit.SECONDS));
            agent.abort();
            release.countDown();
            var result = fut.toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertTrue(seenCancelled.get(), "the hook must observe the run's cancellation signal");
            assertEquals(StopReason.ABORTED, lastAssistant(result).stopReason());
            assertSyntheticAbortTail(recorder.events());
        }
    }

    @Test
    void cancellationWinsOverStop() throws Exception {
        var hookStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var recorder = new RecordingEventSink();
        var stop = new ShouldStopAfterTurn() {
            @Override
            public CompletionStage<Decision> shouldStopAfterTurn(TurnContext turn, CancellationSignal cancellation) {
                hookStarted.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                }
                return CompletableFuture.completedStage(Decision.STOP);
            }
        };
        var client = new ScriptedModelClient(assistantText("first", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, recorder, null, null, stop))) {
            var fut = agent.prompt(user("hi"));
            assertTrue(hookStarted.await(2, TimeUnit.SECONDS));
            agent.abort();
            release.countDown();
            var result = fut.toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(StopReason.ABORTED, lastAssistant(result).stopReason(),
                    "cancellation takes precedence over STOP");
            assertSyntheticAbortTail(recorder.events());
        }
    }

    @Test
    void stopHookBlocksRunAndAbortIsObservable() throws Exception {
        var hookStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var seenCancelled = new AtomicBoolean(false);
        var stop = new ShouldStopAfterTurn() {
            @Override
            public CompletionStage<Decision> shouldStopAfterTurn(TurnContext turn, CancellationSignal cancellation) {
                hookStarted.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                }
                seenCancelled.set(cancellation.isCancelled());
                return CompletableFuture.completedStage(Decision.STOP);
            }
        };
        var client = new ScriptedModelClient(assistantText("first", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, AgentEventSink.noop(), null, null, stop))) {
            var fut = agent.prompt(user("hi"));
            assertTrue(hookStarted.await(2, TimeUnit.SECONDS));
            assertFalse(fut.toCompletableFuture().isDone(), "run must wait for the stop hook stage");
            agent.abort();
            release.countDown();
            var result = fut.toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertTrue(seenCancelled.get(), "the stop hook must observe the run's cancellation signal");
            assertEquals(StopReason.ABORTED, lastAssistant(result).stopReason(),
                    "cancellation wins over STOP");
        }
    }

    @Test
    void abortBeforeFirstTurnDoesNotDuplicateTurnStarted() {
        var recorder = new RecordingEventSink();
        var client = new ScriptedModelClient(assistantText("never", StopReason.STOP));
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        source.cancel();
        var cfg = new AgentLoopConfig(MODEL_A, client, MAPPER, null, null, null, null, null, null, null,
                new RunEventEmitter(recorder), null, null, null);
        loop.runPrompt(List.of(userMsg("hi")), new AgentContext("sys", List.of(), List.of()),
                cfg, source.signal());

        var events = recorder.events();
        assertEquals(1, events.stream().filter(e -> e instanceof AgentEvent.TurnStarted).count(),
                "the run-opening TurnStarted must not be duplicated");
        assertEquals(0, client.receivedRequests().size(), "model must not be called");
        var last = events.get(events.size() - 2);
        assertInstanceOf(AgentEvent.TurnCompleted.class, last);
        assertEquals(StopReason.ABORTED, ((AgentEvent.TurnCompleted) last).assistant().stopReason());
    }

    @Test
    void abortAfterCompletedTurnOpensSyntheticTurn() throws Exception {
        var recorder = new RecordingEventSink();
        var ref = new AtomicReference<Agent>();
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                // cancel as soon as the normal turn completes: the post-turn
                // cancellation check must open a synthetic aborted turn
                if (event instanceof AgentEvent.TurnCompleted tc
                        && !tc.assistant().stopReason().isTerminalFailure()) {
                    ref.get().abort();
                }
                return recorder.emit(event);
            }
        };
        var client = new ScriptedModelClient(assistantText("done", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, sink, null, null, null))) {
            ref.set(agent);
            agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
        assertSyntheticAbortTail(recorder.events());
    }

    @Test
    void abortBeforeToolBatchKeepsOpenTurnWithoutExtraTurnStarted() throws Exception {
        var recorder = new RecordingEventSink();
        var ref = new AtomicReference<Agent>();
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                // cancel when the tool-calling assistant completes, before the
                // turn completes: boundary 3 aborts the still-open turn
                if (event instanceof AgentEvent.MessageCompleted mc
                        && mc.message() instanceof StandardAgentMessage sam
                        && sam.message() instanceof Message.Assistant a
                        && !a.stopReason().isTerminalFailure()) {
                    ref.get().abort();
                }
                return recorder.emit(event);
            }
        };
        var client = new ScriptedModelClient(
                assistantWithToolCalls(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL));
        try (var agent = new Agent(agentConfig(contextWithTools(TestTools.echo()), client,
                sink, null, null, null))) {
            ref.set(agent);
            agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
        var events = recorder.events();
        assertEquals(1, events.stream().filter(e -> e instanceof AgentEvent.TurnStarted).count(),
                "an open-turn abort must not add a second TurnStarted");
        assertEquals(1, events.stream().filter(e -> e instanceof AgentEvent.TurnCompleted).count());
        assertEquals(1, client.receivedRequests().size(), "only the first model call runs");
        assertInstanceOf(AgentEvent.AgentCompleted.class, events.get(events.size() - 1));
        var lastTurn = (AgentEvent.TurnCompleted) events.get(events.size() - 2);
        assertEquals(StopReason.ABORTED, lastTurn.assistant().stopReason());
    }

    @Test
    void abortAfterInnerLoopExitOpensSyntheticTurn() {
        var recorder = new RecordingEventSink();
        var source = new CancellationSource();
        var steering = new PendingMessageSource() {
            private int drains;

            @Override
            public List<AgentMessage> drain() {
                // the initial poll passes; the post-turn poll cancels so the
                // post-inner-loop boundary observes the cancellation
                if (++drains >= 2) {
                    source.cancel();
                }
                return List.of();
            }
        };
        var client = new ScriptedModelClient(assistantText("done", StopReason.STOP));
        var cfg = new AgentLoopConfig(MODEL_A, client, MAPPER, null, null, null, null, null,
                steering, null, new RunEventEmitter(recorder), null, null, null);
        var loop = new AgentLoop(executor);
        loop.runPrompt(List.of(userMsg("hi")), new AgentContext("sys", List.of(), List.of()),
                cfg, source.signal());

        assertEquals(1, client.receivedRequests().size(), "no second model call");
        assertSyntheticAbortTail(recorder.events());
    }

    @Test
    void abortAtNextIterationBoundaryOpensSyntheticTurn() {
        var recorder = new RecordingEventSink();
        var source = new CancellationSource();
        var steering = new PendingMessageSource() {
            private int drains;

            @Override
            public List<AgentMessage> drain() {
                if (++drains >= 2) {
                    source.cancel();
                }
                return List.of();
            }
        };
        var client = new ScriptedModelClient(
                assistantWithToolCalls(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL));
        var cfg = new AgentLoopConfig(MODEL_A, client, MAPPER, null, null, null, null, null,
                steering, null, new RunEventEmitter(recorder), null, null, null);
        var loop = new AgentLoop(executor);
        loop.runPrompt(List.of(userMsg("hi")),
                new AgentContext("sys", List.of(), List.of(TestTools.echo())),
                cfg, source.signal());

        assertEquals(1, client.receivedRequests().size(), "no second model call");
        assertSyntheticAbortTail(recorder.events());
    }

    @Test
    void terminalModelFailureSkipsTurnHooks() throws Exception {
        var prepareCalls = new AtomicInteger();
        var stopCalls = new AtomicInteger();
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                prepareCalls.incrementAndGet();
                return CompletableFuture.completedStage(NextTurnUpdate.keep());
            }
        };
        var stop = new ShouldStopAfterTurn() {
            @Override
            public CompletionStage<Decision> shouldStopAfterTurn(TurnContext turn, CancellationSignal cancellation) {
                stopCalls.incrementAndGet();
                return CompletableFuture.completedStage(Decision.CONTINUE);
            }
        };
        var client = new ScriptedModelClient(
                new Message.Assistant(List.of(), StopReason.ERROR, "boom", Usage.zero(), T1));
        try (var agent = new Agent(agentConfig(client, AgentEventSink.noop(), null, prepare, stop))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(0, prepareCalls.get());
            assertEquals(0, stopCalls.get());
            var last = (Message.Assistant) ((StandardAgentMessage) result.context().messages().get(1)).message();
            assertEquals(StopReason.ERROR, last.stopReason());
        }
    }

    // --- failure normalization ---

    @Test
    void prepareNextTurnSyncThrowNormalizesToErrorAssistant() throws Exception {
        var recorder = new RecordingEventSink();
        var stopCalls = new AtomicInteger();
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                throw new RuntimeException("boom");
            }
        };
        var stop = new ShouldStopAfterTurn() {
            @Override
            public CompletionStage<Decision> shouldStopAfterTurn(TurnContext turn, CancellationSignal cancellation) {
                stopCalls.incrementAndGet();
                return CompletableFuture.completedStage(Decision.CONTINUE);
            }
        };
        var client = new ScriptedModelClient(assistantText("first", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, recorder, null, prepare, stop))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(1, client.receivedRequests().size(), "no second model call after hook failure");
            assertEquals(0, stopCalls.get(), "stop hook must not run after prepare failure");
            assertEquals(StopReason.ERROR, lastAssistant(result).stopReason());
            assertTrue(lastAssistant(result).errorMessage().contains("prepareNextTurn failed"));
            assertSyntheticFailureLifecycle(recorder.events(), "prepareNextTurn failed");
        }
    }

    @Test
    void prepareNextTurnExceptionalStageNormalizes() throws Exception {
        var recorder = new RecordingEventSink();
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                return CompletableFuture.failedFuture(new RuntimeException("boom"));
            }
        };
        var client = new ScriptedModelClient(assistantText("first", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, recorder, null, prepare, null))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, lastAssistant(result).stopReason());
            assertSyntheticFailureLifecycle(recorder.events(), "prepareNextTurn failed");
        }
    }

    @Test
    void prepareNextTurnNullStageNormalizes() throws Exception {
        var recorder = new RecordingEventSink();
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                return null;
            }
        };
        var client = new ScriptedModelClient(assistantText("first", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, recorder, null, prepare, null))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, lastAssistant(result).stopReason());
            assertSyntheticFailureLifecycle(recorder.events(), "prepareNextTurn returned null stage");
        }
    }

    @Test
    void prepareNextTurnNullUpdateNormalizes() throws Exception {
        var recorder = new RecordingEventSink();
        var prepare = new PrepareNextTurn() {
            @Override
            public CompletionStage<NextTurnUpdate> prepareNextTurn(TurnContext turn, CancellationSignal cancellation) {
                return CompletableFuture.completedStage(null);
            }
        };
        var client = new ScriptedModelClient(assistantText("first", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, recorder, null, prepare, null))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, lastAssistant(result).stopReason());
            assertSyntheticFailureLifecycle(recorder.events(), "prepareNextTurn returned null update");
        }
    }

    @Test
    void shouldStopAfterTurnSyncThrowNormalizesToErrorAssistant() throws Exception {
        var recorder = new RecordingEventSink();
        var stop = new ShouldStopAfterTurn() {
            @Override
            public CompletionStage<Decision> shouldStopAfterTurn(TurnContext turn, CancellationSignal cancellation) {
                throw new RuntimeException("boom");
            }
        };
        var client = new ScriptedModelClient(assistantText("first", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, recorder, null, null, stop))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, lastAssistant(result).stopReason());
            assertSyntheticFailureLifecycle(recorder.events(), "shouldStopAfterTurn failed");
        }
    }

    @Test
    void shouldStopAfterTurnNullDecisionNormalizes() throws Exception {
        var recorder = new RecordingEventSink();
        var stop = new ShouldStopAfterTurn() {
            @Override
            public CompletionStage<Decision> shouldStopAfterTurn(TurnContext turn, CancellationSignal cancellation) {
                return CompletableFuture.completedStage(null);
            }
        };
        var client = new ScriptedModelClient(assistantText("first", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, recorder, null, null, stop))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, lastAssistant(result).stopReason());
            assertSyntheticFailureLifecycle(recorder.events(), "shouldStopAfterTurn returned null decision");
        }
    }

    @Test
    void shouldStopAfterTurnNullStageNormalizes() throws Exception {
        var recorder = new RecordingEventSink();
        var stop = new ShouldStopAfterTurn() {
            @Override
            public CompletionStage<Decision> shouldStopAfterTurn(TurnContext turn, CancellationSignal cancellation) {
                return null;
            }
        };
        var client = new ScriptedModelClient(assistantText("first", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, recorder, null, null, stop))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, lastAssistant(result).stopReason());
            assertSyntheticFailureLifecycle(recorder.events(), "shouldStopAfterTurn returned null stage");
        }
    }

    @Test
    void shouldStopAfterTurnExceptionalStageNormalizes() throws Exception {
        var recorder = new RecordingEventSink();
        var stop = new ShouldStopAfterTurn() {
            @Override
            public CompletionStage<Decision> shouldStopAfterTurn(TurnContext turn, CancellationSignal cancellation) {
                return CompletableFuture.failedFuture(new RuntimeException("boom"));
            }
        };
        var client = new ScriptedModelClient(assistantText("first", StopReason.STOP));
        try (var agent = new Agent(agentConfig(client, recorder, null, null, stop))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(StopReason.ERROR, lastAssistant(result).stopReason());
            assertSyntheticFailureLifecycle(recorder.events(), "shouldStopAfterTurn failed");
        }
    }

    @Test
    void turnContextValidatesAndDefensivelyCopies() {
        var ctx = new AgentContext("sys", List.of(), List.of());
        var assistant = assistantText("done", StopReason.STOP);
        assertThrows(NullPointerException.class, () -> new TurnContext(null, List.of(), ctx, List.of()));
        assertThrows(NullPointerException.class, () -> new TurnContext(assistant, null, ctx, List.of()));
        assertThrows(NullPointerException.class, () -> new TurnContext(assistant, List.of(), null, List.of()));
        assertThrows(NullPointerException.class, () -> new TurnContext(assistant, List.of(), ctx, null));

        var mutableResults = new ArrayList<Message.ToolResultMessage>();
        var mutableLog = new ArrayList<AgentMessage>();
        var turn = new TurnContext(assistant, mutableResults, ctx, mutableLog);
        mutableResults.add(new Message.ToolResultMessage(
                "c1", "echo", List.of(new Content.Text("late")), false, T1));
        mutableLog.add(userMsg("late"));
        assertEquals(0, turn.toolResults().size(), "construction must snapshot the lists");
        assertEquals(0, turn.newMessages().size());
        assertThrows(UnsupportedOperationException.class, () -> turn.toolResults().add(
                new Message.ToolResultMessage("c2", "echo", List.of(), false, T1)));
        assertThrows(UnsupportedOperationException.class, () -> turn.newMessages().add(userMsg("x")));
    }

    @Test
    void nextTurnUpdateRejectsNullComponents() {
        var context = new AgentContext("sys", List.of(), List.of());
        assertThrows(NullPointerException.class,
                () -> new NextTurnUpdate(null, Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new NextTurnUpdate(Optional.empty(), null, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new NextTurnUpdate(Optional.empty(), Optional.empty(), null));
        assertEquals(NextTurnUpdate.keep(), new NextTurnUpdate(Optional.empty(), Optional.empty(), Optional.empty()));
    }
}
