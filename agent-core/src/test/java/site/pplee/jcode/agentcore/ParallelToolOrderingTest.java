package site.pplee.jcode.agentcore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.support.RecordingEventSink;
import site.pplee.jcode.agentcore.support.ScriptedModelClient;
import site.pplee.jcode.agentcore.support.TestTools;
import site.pplee.jcode.agentcore.tool.AfterToolCall;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.BeforeToolCall;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.agentcore.turn.ShouldStopAfterTurn;

import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave 5 parallel tool ordering contract tests: serial prepare in source
 * order, concurrent execute/finalize, completion-order {@code ToolCompleted}
 * events, source-order transcript write-back, accepted-update draining,
 * interrupt/delivery-failure drain semantics, the prepare/submit
 * infrastructure failure state machine, the failure normalization matrix,
 * atomic {@code AgentState} reduction, and cancellation during prepare.
 * All concurrency control uses latches, bounded future waits, and state
 * checks; no sleeps and no unbounded joins. Releases are always guarded by
 * finally so a failed assertion cannot leak a blocked run.
 */
@Timeout(15)
class ParallelToolOrderingTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef MODEL = new ModelRef("test", "test-api", "test-model");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Binary name of the package-private per-tool update sink. */
    private static final String SINK_CLASS = "site.pplee.jcode.agentcore.ToolCallExecutor$LoopToolUpdateSink";

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

    private static Message.Assistant assistant(List<Content> content, StopReason reason) {
        return Message.Assistant.of(content, reason, T1);
    }

    private static Message.Assistant assistantText(String text, StopReason reason) {
        return assistant(List.of(new Content.Text(text)), reason);
    }

    private static Content.ToolCall toolCall(String id, String name) {
        return new Content.ToolCall(id, name, MAPPER.getNodeFactory().textNode("hello"));
    }

    private static AgentContext contextWithTools(AgentTool<?>... tools) {
        var list = new ArrayList<AgentTool<?>>();
        for (var t : tools) {
            list.add(t);
        }
        return new AgentContext("sys", List.of(), List.copyOf(list));
    }

    private AgentLoopConfig config(ModelClient client, AgentEventSink sink) {
        return new AgentLoopConfig(MODEL, client, MAPPER, null, null, null,
                null, null, null, null, new RunEventEmitter(sink));
    }

    private AgentLoopConfig configWithHooks(
            ModelClient client, AgentEventSink sink,
            BeforeToolCall before, AfterToolCall after) {
        return new AgentLoopConfig(MODEL, client, MAPPER, null, null, null,
                before, after, null, null, new RunEventEmitter(sink));
    }

    private AgentLoopConfig configWithTurnControl(
            ModelClient client, AgentEventSink sink, ShouldStopAfterTurn stop) {
        return new AgentLoopConfig(MODEL, client, MAPPER, null, null, null,
                null, null, null, null, new RunEventEmitter(sink),
                null, null, stop);
    }

    private static AgentConfig agentConfig(AgentContext ctx, ModelClient client, AgentEventSink sink) {
        return new AgentConfig(ctx, MODEL, client, MAPPER, null, null, null,
                null, null, sink, null, null);
    }

    private record RunResult(LoopResult result, RecordingEventSink sink) {}

    private RunResult run(ModelClient client, AgentContext ctx) {
        var sink = new RecordingEventSink();
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var result = loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, sink), source.signal());
        return new RunResult(result, sink);
    }

    private RunResult runWithHooks(
            ModelClient client, AgentContext ctx,
            BeforeToolCall before, AfterToolCall after) {
        var sink = new RecordingEventSink();
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var result = loop.runPrompt(List.of(userMsg("hi")), ctx,
                configWithHooks(client, sink, before, after), source.signal());
        return new RunResult(result, sink);
    }

    /** Bounded wait for a run to settle; never blocks a test indefinitely. */
    private static <T> T awaitSettled(CompletableFuture<T> fut) throws Exception {
        return fut.get(5, TimeUnit.SECONDS);
    }

    /** Tool that succeeds instantly with its own name as text content. */
    private static AgentTool<Object> quickTool(String name) {
        return new AgentTool<Object>() {
            @Override public String name() { return name; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text(name))));
            }
        };
    }

    private static List<String> toolCallIds(List<AgentEvent> events) {
        return events.stream()
                .filter(e -> e instanceof AgentEvent.ToolStarted)
                .map(e -> ((AgentEvent.ToolStarted) e).call().id())
                .toList();
    }

    private static List<String> completedIds(List<AgentEvent> events) {
        return events.stream()
                .filter(e -> e instanceof AgentEvent.ToolCompleted)
                .map(e -> ((AgentEvent.ToolCompleted) e).result().toolCallId())
                .toList();
    }

    private static List<String> startedToolResultIds(List<AgentEvent> events) {
        return events.stream()
                .filter(e -> e instanceof AgentEvent.MessageStarted)
                .map(e -> ((AgentEvent.MessageStarted) e).message())
                .filter(m -> m instanceof StandardAgentMessage sam
                        && sam.message() instanceof Message.ToolResultMessage)
                .map(m -> ((Message.ToolResultMessage) ((StandardAgentMessage) m).message()).toolCallId())
                .toList();
    }

    private static List<String> completedToolResultIds(List<AgentEvent> events) {
        return events.stream()
                .filter(e -> e instanceof AgentEvent.MessageCompleted)
                .map(e -> ((AgentEvent.MessageCompleted) e).message())
                .filter(m -> m instanceof StandardAgentMessage sam
                        && sam.message() instanceof Message.ToolResultMessage)
                .map(m -> ((Message.ToolResultMessage) ((StandardAgentMessage) m).message()).toolCallId())
                .toList();
    }

    private static List<String> transcriptToolResultIds(AgentContext ctx) {
        return ctx.messages().stream()
                .filter(m -> m instanceof StandardAgentMessage sam
                        && sam.message() instanceof Message.ToolResultMessage)
                .map(m -> ((Message.ToolResultMessage) ((StandardAgentMessage) m).message()).toolCallId())
                .toList();
    }

    private static List<String> requestToolResultIds(ModelRequest request) {
        return request.messages().stream()
                .filter(m -> m instanceof Message.ToolResultMessage)
                .map(m -> ((Message.ToolResultMessage) m).toolCallId())
                .toList();
    }

    private static boolean containsMessage(Throwable t, String fragment) {
        for (var cur = t; cur != null; cur = cur.getCause()) {
            if (cur.getMessage() != null && cur.getMessage().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bounded stack-observation helper: waits until {@code thread}'s stack
     * contains a frame for {@code className}.{@code methodName}, sampling with
     * {@link LockSupport#parkNanos} (no {@code Thread.sleep}). Fails with a
     * clear message including the observed stack if the frame never appears
     * within the timeout. Used to positively confirm that a worker has entered
     * a package-private blocking call (for example
     * {@code LoopToolUpdateSink.settle}) before a test releases a controlled
     * stage; no production hook is added for this.
     */
    private static void awaitStack(Thread thread, String className, String methodName, long timeoutMs)
            throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean found = false;
        while (System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("interrupted while awaiting thread stack");
            }
            for (var frame : thread.getStackTrace()) {
                if (frame.getClassName().equals(className) && frame.getMethodName().equals(methodName)) {
                    found = true;
                    break;
                }
            }
            if (found) {
                return;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        var observed = new ArrayList<String>();
        for (var frame : thread.getStackTrace()) {
            observed.add(frame.getClassName() + "." + frame.getMethodName());
            if (observed.size() >= 12) {
                break;
            }
        }
        throw new AssertionError("thread " + thread.getName() + " did not reach "
                + className + "." + methodName + " within " + timeoutMs + "ms; observed stack: " + observed);
    }

    // --- Slice 1: atomic AgentState reduction (Agent-level regression) ---

    @Test
    void pendingToolCallsNeverReappearsAfterCompletionWhileOthersStreamUpdates() throws Exception {
        var updateSink = new AtomicReference<ToolUpdateSink>();
        var c1Entered = new CountDownLatch(1);
        var releaseC1 = new CountDownLatch(1);
        var c2Completed = new CountDownLatch(1);
        var c2SnapshotIndex = new AtomicInteger(-1);
        var snapshots = new CopyOnWriteArrayList<Set<String>>();
        var agentRef = new AtomicReference<Agent>();

        var slow = new AgentTool<Object>() {
            @Override public String name() { return "slow_update"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                updateSink.set(updates);
                updates.update(new Content.Text("first")).toCompletableFuture().join();
                c1Entered.countDown();
                try {
                    releaseC1.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                }
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("slow"))));
            }
        };
        var fast = quickTool("fast");
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                if (event instanceof AgentEvent.ToolCompleted tc
                        && tc.result().toolCallId().equals("c2")) {
                    c2SnapshotIndex.set(snapshots.size());
                    c2Completed.countDown();
                }
                snapshots.add(agentRef.get().state().pendingToolCalls());
                return CompletableFuture.completedStage(null);
            }
        };
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "slow_update"), toolCall("c2", "fast")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        var ctx = contextWithTools(slow, fast);
        try (var agent = new Agent(agentConfig(ctx, client, sink))) {
            agentRef.set(agent);
            var fut = agent.prompt(user("hi")).toCompletableFuture();

            assertTrue(c1Entered.await(2, TimeUnit.SECONDS), "slow tool must start");
            assertTrue(c2Completed.await(2, TimeUnit.SECONDS),
                    "fast tool must complete while the slow tool is still running");

            // The slow tool keeps streaming updates while the batch drain waits on it.
            for (int i = 0; i < 3; i++) {
                updateSink.get().update(new Content.Text("p" + i)).toCompletableFuture().join();
            }

            int c2Idx = c2SnapshotIndex.get();
            assertTrue(c2Idx >= 0, "c2 completion must have been observed");
            try {
                // every snapshot from the c2-completion reduction onward must
                // never contain c2 again, even while c1 keeps streaming updates
                for (int i = c2Idx; i < snapshots.size(); i++) {
                    assertFalse(snapshots.get(i).contains("c2"),
                            "completed id must never reappear in pendingToolCalls");
                }
            } finally {
                releaseC1.countDown();
            }
            awaitSettled(fut);
            assertEquals(Set.of(), agent.state().pendingToolCalls(),
                    "pending set must be empty after the run");
        }
    }

    // --- Slice 2: serial prepare in source order ---

    @Test
    void prepareRunsInSourceOrderAndBlocksLaterStarts() throws Exception {
        var beforeCalls = new CopyOnWriteArrayList<String>();
        var firstBeforeStarted = new CountDownLatch(1);
        var firstBeforeRelease = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<AgentEvent>();

        BeforeToolCall before = (call, tool, args, ctx, c) -> {
            beforeCalls.add(call.id());
            if (call.id().equals("c1")) {
                firstBeforeStarted.countDown();
                try {
                    firstBeforeRelease.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                }
            }
            return CompletableFuture.completedStage(new BeforeToolCall.Decision.Proceed());
        };
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                events.add(event);
                if (event instanceof AgentEvent.ToolStarted ts && ts.call().id().equals("c2")) {
                    secondStarted.countDown();
                }
                return CompletableFuture.completedStage(null);
            }
        };
        var echo = TestTools.sideEffect("echo", () -> { });
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "echo"), toolCall("c2", "echo")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        var ctx = contextWithTools(echo);
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx,
                        configWithHooks(client, sink, before, AfterToolCall.noop()), source.signal()),
                executor);

        try {
            assertTrue(firstBeforeStarted.await(2, TimeUnit.SECONDS), "first prepare must run");
            // While the loop thread is blocked inside the first prepare, the
            // second ToolStarted cannot have been emitted and the second
            // prepare cannot have run: deterministic state checks, no time window.
            assertEquals(1, secondStarted.getCount(),
                    "second ToolStarted must not fire while the first prepare is blocked");
            assertEquals(List.of("c1"), beforeCalls, "only the first prepare may run");

            firstBeforeRelease.countDown();
            awaitSettled(fut);

            assertEquals(List.of("c1", "c2"), beforeCalls, "prepare must run in source order");
            assertEquals(List.of("c1", "c2"), toolCallIds(events), "ToolStarted must be in source order");
        } finally {
            firstBeforeRelease.countDown();
        }
    }

    @Test
    void noExecuteStartsBeforeAllPreparesComplete() throws Exception {
        var c1ExecStarted = new CountDownLatch(1);
        var secondBeforeStarted = new CountDownLatch(1);
        var secondBeforeRelease = new CountDownLatch(1);

        BeforeToolCall before = (call, tool, args, ctx, c) -> {
            if (call.id().equals("c2")) {
                secondBeforeStarted.countDown();
                try {
                    secondBeforeRelease.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                }
            }
            return CompletableFuture.completedStage(new BeforeToolCall.Decision.Proceed());
        };
        var t1 = new AgentTool<Object>() {
            @Override public String name() { return "t1"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                c1ExecStarted.countDown();
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("ok"))));
            }
        };
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "t1"), toolCall("c2", "echo")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        var ctx = contextWithTools(t1, TestTools.echo());
        var recorder = new RecordingEventSink();
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx,
                        configWithHooks(client, recorder, before, AfterToolCall.noop()), source.signal()),
                executor);

        try {
            assertTrue(secondBeforeStarted.await(2, TimeUnit.SECONDS), "second prepare must start");
            // Tasks are submitted only after the serial prepare pass finishes,
            // so while the loop thread is inside the second prepare the first
            // execute cannot have started: deterministic state check.
            assertEquals(1, c1ExecStarted.getCount(),
                    "no execute may start before the whole prepare pass completes");

            secondBeforeRelease.countDown();
            awaitSettled(fut);
            assertEquals(0, c1ExecStarted.getCount(), "the first tool must eventually execute");
        } finally {
            secondBeforeRelease.countDown();
        }
    }

    // --- Slice 3: dual ordering (completion vs source) ---

    @Test
    void toolCompletedInCompletionOrderButTranscriptInSourceOrder() throws Exception {
        var c1Blocked = new CountDownLatch(1);
        var releaseC1 = new CountDownLatch(1);
        var c2Completed = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var turnResultsList = new CopyOnWriteArrayList<List<Message.ToolResultMessage>>();

        var b1 = TestTools.blocking("b1", c1Blocked, releaseC1, ToolExecutionMode.PARALLEL);
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                events.add(event);
                if (event instanceof AgentEvent.ToolCompleted tc && tc.result().toolCallId().equals("c2")) {
                    c2Completed.countDown();
                }
                return CompletableFuture.completedStage(null);
            }
        };
        ShouldStopAfterTurn stop = (turn, c) -> {
            turnResultsList.add(turn.toolResults());
            return CompletableFuture.completedStage(ShouldStopAfterTurn.Decision.CONTINUE);
        };
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "b1"), toolCall("c2", "echo")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        var ctx = contextWithTools(b1, TestTools.echo());
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx,
                        configWithTurnControl(client, sink, stop), source.signal()),
                executor);

        try {
            assertTrue(c1Blocked.await(2, TimeUnit.SECONDS), "first tool must be executing");
            assertTrue(c2Completed.await(2, TimeUnit.SECONDS),
                    "the faster tool's ToolCompleted must arrive while the first is still running");
            releaseC1.countDown();
            var result = awaitSettled(fut);

            // lifecycle events follow completion order; starts follow source order
            assertEquals(List.of("c2", "c1"), completedIds(events), "ToolCompleted must follow completion order");
            assertEquals(List.of("c1", "c2"), toolCallIds(events), "ToolStarted must follow source order");
            // tool-result messages, transcript, and turn payload follow source order
            assertEquals(List.of("c1", "c2"), startedToolResultIds(events),
                    "tool-result MessageStarted must follow source order");
            assertEquals(List.of("c1", "c2"), completedToolResultIds(events),
                    "tool-result MessageCompleted must follow source order");
            assertEquals(List.of("c1", "c2"), transcriptToolResultIds(result.context()),
                    "transcript must follow source order");
            var turnResults = events.stream()
                    .filter(e -> e instanceof AgentEvent.TurnCompleted)
                    .map(e -> ((AgentEvent.TurnCompleted) e).toolResults())
                    .findFirst().orElseThrow();
            assertEquals(List.of("c1", "c2"),
                    turnResults.stream().map(Message.ToolResultMessage::toolCallId).toList(),
                    "TurnCompleted.toolResults must follow source order");
            assertEquals(List.of("c1", "c2"),
                    turnResultsList.get(0).stream().map(Message.ToolResultMessage::toolCallId).toList(),
                    "TurnContext.toolResults must follow source order");
            assertEquals(List.of("c1", "c2"), requestToolResultIds(client.receivedRequests().get(1)),
                    "the next model request must carry tool results in source order");
        } finally {
            releaseC1.countDown();
        }
    }

    // --- Slice 4: finalize runs concurrently and gates its own completion ---

    @Test
    void afterHookRunsConcurrentlyAndGatesItsOwnCompletion() throws Exception {
        var after1Started = new CountDownLatch(1);
        var after1Release = new CountDownLatch(1);
        var c2Completed = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<AgentEvent>();

        AfterToolCall after = (call, tool, result, ctx, c) -> {
            if (call.id().equals("c1")) {
                after1Started.countDown();
                try {
                    after1Release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                }
            }
            return CompletableFuture.completedStage(result);
        };
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                events.add(event);
                if (event instanceof AgentEvent.ToolCompleted tc && tc.result().toolCallId().equals("c2")) {
                    c2Completed.countDown();
                }
                return CompletableFuture.completedStage(null);
            }
        };
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "t1"), toolCall("c2", "t2")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        var ctx = contextWithTools(quickTool("t1"), quickTool("t2"));
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx,
                        configWithHooks(client, sink, BeforeToolCall.noop(), after), source.signal()),
                executor);

        try {
            assertTrue(after1Started.await(2, TimeUnit.SECONDS), "first finalize must start");
            assertTrue(c2Completed.await(2, TimeUnit.SECONDS),
                    "the second tool must complete while the first finalize is blocked");
            assertEquals(List.of("c2"), completedIds(events),
                    "only the second tool may complete while the first finalize blocks");
            after1Release.countDown();
            awaitSettled(fut);
            assertEquals(List.of("c2", "c1"), completedIds(events));
        } finally {
            after1Release.countDown();
        }
    }

    // --- Slice 5: accepted updates are drained before finalize ---

    @Test
    void acceptedUpdateIsDrainedBeforeFinalizeAndToolCompleted() throws Exception {
        var updateStage = new CompletableFuture<Void>();
        var updateReached = new CountDownLatch(1);
        var executeEntered = new CountDownLatch(1);
        var releaseExecute = new CountDownLatch(1);
        var afterHookStarted = new CountDownLatch(1);
        var updateSink = new AtomicReference<ToolUpdateSink>();
        var workerThread = new AtomicReference<Thread>();
        var events = new CopyOnWriteArrayList<AgentEvent>();

        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                events.add(event);
                if (event instanceof AgentEvent.ToolUpdate) {
                    updateReached.countDown();
                    return updateStage;
                }
                return CompletableFuture.completedStage(null);
            }
        };
        AfterToolCall after = (call, tool, result, ctx, c) -> {
            afterHookStarted.countDown();
            return CompletableFuture.completedStage(result);
        };
        var tool = new AgentTool<Object>() {
            @Override public String name() { return "update_tool"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                workerThread.set(Thread.currentThread());
                updateSink.set(updates);
                executeEntered.countDown();
                try {
                    releaseExecute.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                }
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("done"))));
            }
        };
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "update_tool")), StopReason.TOOL_CALL),
                assistantText("after", StopReason.STOP));
        var ctx = contextWithTools(tool);
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx,
                        configWithHooks(client, sink, BeforeToolCall.noop(), after), source.signal()),
                executor);

        try {
            assertTrue(executeEntered.await(2, TimeUnit.SECONDS), "tool execute must start");
            // Deliver the accepted update from a separate thread while the tool's
            // execute is still pending: the update passes the accepting check and
            // blocks on the controlled sink stage. The test thread must never call
            // update() itself here — it would block on the same stage it later
            // completes.
            var updateFuture = CompletableFuture.runAsync(() ->
                            updateSink.get().update(new Content.Text("partial")).toCompletableFuture().join(),
                    executor);
            assertTrue(updateReached.await(2, TimeUnit.SECONDS), "accepted update must reach the sink");
            assertFalse(updateFuture.isDone(), "accepted update must still be in flight");

            releaseExecute.countDown();
            // Positive evidence the worker is draining: its stack must be
            // inside LoopToolUpdateSink.settle() waiting for the accepted
            // update before we release it. Only then are the "not yet
            // finalized" assertions meaningful.
            awaitStack(workerThread.get(), SINK_CLASS, "settle", 5_000);
            assertEquals(1, afterHookStarted.getCount(),
                    "finalize must not start while an accepted update is still in flight");
            assertFalse(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolCompleted),
                    "ToolCompleted must wait until the accepted update is drained");

            updateStage.complete(null);
            updateFuture.get(5, TimeUnit.SECONDS);
            assertTrue(afterHookStarted.await(2, TimeUnit.SECONDS),
                    "finalize must run after the accepted update drains");
            awaitSettled(fut);

            var toolEvents = events.stream()
                    .filter(e -> e instanceof AgentEvent.ToolStarted
                            || e instanceof AgentEvent.ToolUpdate
                            || e instanceof AgentEvent.ToolCompleted)
                    .toList();
            assertEquals(3, toolEvents.size(), "lifecycle must be ToolStarted -> ToolUpdate -> ToolCompleted");
            var started = assertInstanceOf(AgentEvent.ToolStarted.class, toolEvents.get(0));
            assertEquals("c1", started.call().id());
            var update = assertInstanceOf(AgentEvent.ToolUpdate.class, toolEvents.get(1));
            assertEquals("partial", ((Content.Text) update.update()).text());
            var completed = assertInstanceOf(AgentEvent.ToolCompleted.class, toolEvents.get(2));
            assertEquals("c1", completed.result().toolCallId());

            // updates delivered after settlement are silently dropped
            updateSink.get().update(new Content.Text("late")).toCompletableFuture().join();
            var updateCount = events.stream().filter(e -> e instanceof AgentEvent.ToolUpdate).count();
            assertEquals(1, updateCount, "late update after settlement must be dropped");
        } finally {
            releaseExecute.countDown();
            updateStage.complete(null);
        }
    }

    // --- Slice 6: immediate prepare failure ---

    @Test
    void immediatePrepareFailureCompletesBeforeNextStartAndKeepsSourceSlot() {
        var execCount = new AtomicInteger();
        var echo = TestTools.sideEffect("echo", execCount::incrementAndGet);
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "ghost"), toolCall("c2", "echo")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        var ctx = contextWithTools(echo);
        var run = run(client, ctx);

        assertEquals(1, execCount.get(), "prepared tool must execute");
        var toolEvents = run.sink().events().stream()
                .filter(e -> e instanceof AgentEvent.ToolStarted || e instanceof AgentEvent.ToolCompleted)
                .toList();
        assertEquals(4, toolEvents.size());
        assertInstanceOf(AgentEvent.ToolStarted.class, toolEvents.get(0));
        assertEquals("c1", ((AgentEvent.ToolStarted) toolEvents.get(0)).call().id());
        var c1Completed = assertInstanceOf(AgentEvent.ToolCompleted.class, toolEvents.get(1));
        assertTrue(c1Completed.result().error(), "unknown tool must produce an error result");
        assertInstanceOf(AgentEvent.ToolStarted.class, toolEvents.get(2));
        assertEquals("c2", ((AgentEvent.ToolStarted) toolEvents.get(2)).call().id());
        assertInstanceOf(AgentEvent.ToolCompleted.class, toolEvents.get(3));

        // transcript stays in source order despite the early completion
        assertEquals(List.of("c1", "c2"), transcriptToolResultIds(run.result().context()));
        var turnResults = run.sink().events().stream()
                .filter(e -> e instanceof AgentEvent.TurnCompleted)
                .map(e -> ((AgentEvent.TurnCompleted) e).toolResults())
                .findFirst().orElseThrow();
        assertEquals(List.of("c1", "c2"),
                turnResults.stream().map(Message.ToolResultMessage::toolCallId).toList());
    }

    // --- Slice 7: infrastructure failure state machine and drain ---

    @Test
    void toolCompletedDeliveryFailureDrainsAcceptedTasksBeforeFailing() throws Exception {
        var c1Blocked = new CountDownLatch(1);
        var releaseC1 = new CountDownLatch(1);
        var c2FailureObserved = new CountDownLatch(1);
        var recorded = new CopyOnWriteArrayList<AgentEvent>();

        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                recorded.add(event);
                if (event instanceof AgentEvent.ToolCompleted tc && tc.result().toolCallId().equals("c2")) {
                    c2FailureObserved.countDown();
                    return CompletableFuture.failedFuture(new RuntimeException("sink boom"));
                }
                return CompletableFuture.completedStage(null);
            }
        };
        var b1 = TestTools.blocking("b1", c1Blocked, releaseC1, ToolExecutionMode.PARALLEL);
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "b1"), toolCall("c2", "t2")), StopReason.TOOL_CALL),
                assistantText("never", StopReason.STOP));
        var ctx = contextWithTools(b1, quickTool("t2"));
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, sink), source.signal()),
                executor);

        try {
            assertTrue(c1Blocked.await(2, TimeUnit.SECONDS), "accepted task must be executing");
            assertTrue(c2FailureObserved.await(2, TimeUnit.SECONDS),
                    "the c2 delivery failure must be observed while c1 is still running");
            assertFalse(fut.isDone(), "run must not settle while an accepted task is still draining");
            releaseC1.countDown();
            var ex = assertThrows(ExecutionException.class, () -> fut.get(5, TimeUnit.SECONDS));
            assertTrue(containsMessage(ex, "sink boom"), "first delivery failure must propagate");
            assertFalse(recorded.stream().anyMatch(e -> e instanceof AgentEvent.ToolCompleted tc
                            && tc.result().toolCallId().equals("c1")),
                    "no lifecycle event may be emitted after the first failure");
        } finally {
            releaseC1.countDown();
        }
    }

    @Test
    void prepareDeliveryFailureSkipsUnsubmittedEntriesAndClearsPendingState() throws Exception {
        var execCount = new AtomicInteger();
        var echo = TestTools.sideEffect("echo", execCount::incrementAndGet);
        var recorded = new CopyOnWriteArrayList<AgentEvent>();
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                recorded.add(event);
                if (event instanceof AgentEvent.ToolCompleted tc && tc.result().toolCallId().equals("c1")) {
                    return CompletableFuture.failedFuture(new RuntimeException("prepare sink boom"));
                }
                return CompletableFuture.completedStage(null);
            }
        };
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "ghost"), toolCall("c2", "echo")), StopReason.TOOL_CALL),
                assistantText("never", StopReason.STOP));
        var ctx = contextWithTools(echo);
        try (var agent = new Agent(agentConfig(ctx, client, sink))) {
            var fut = agent.prompt(user("hi")).toCompletableFuture();
            var ex = assertThrows(ExecutionException.class, () -> fut.get(5, TimeUnit.SECONDS));
            assertTrue(containsMessage(ex, "prepare sink boom"), "delivery failure must propagate");
            assertEquals(0, execCount.get(), "unsubmitted prepared entries must never execute");
            assertFalse(recorded.stream().anyMatch(e -> e instanceof AgentEvent.ToolStarted ts
                            && ts.call().id().equals("c2")),
                    "the second call must never be started");
            assertEquals(Set.of(), agent.state().pendingToolCalls(), "pending set must be cleared");
        }
    }

    @Test
    void submitRejectionDrainsAcceptedTasksAndPropagatesFirstRejection() throws Exception {
        var c1Blocked = new CountDownLatch(1);
        var releaseC1 = new CountDownLatch(1);
        var execCount = new AtomicInteger();
        var b1 = TestTools.blocking("b1", c1Blocked, releaseC1, ToolExecutionMode.PARALLEL);
        var t2 = TestTools.sideEffect("t2", execCount::incrementAndGet);
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "b1"), toolCall("c2", "t2")), StopReason.TOOL_CALL),
                assistantText("never", StopReason.STOP));
        var ctx = contextWithTools(b1, t2);
        // run task + c1 accepted; c2 submission rejected
        try (var rejecting = new RejectingExecutor(2)) {
            var loop = new AgentLoop(rejecting);
            var source = new CancellationSource();
            var recorder = new RecordingEventSink();
            var fut = CompletableFuture.supplyAsync(() ->
                    loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, recorder), source.signal()),
                    rejecting);

            try {
                assertTrue(c1Blocked.await(2, TimeUnit.SECONDS), "accepted task must run");
                assertTrue(rejecting.rejectionObserved().await(2, TimeUnit.SECONDS),
                        "the c2 submission must be rejected");
                assertFalse(fut.isDone(), "run must drain accepted tasks before failing");
                assertEquals(0, execCount.get(), "rejected entry must never execute");
                releaseC1.countDown();
                var ex = assertThrows(ExecutionException.class, () -> fut.get(5, TimeUnit.SECONDS));
                assertTrue(containsMessage(ex, "rejected by test executor"), "first rejection must propagate");
            } finally {
                releaseC1.countDown();
            }
        }
    }

    @Test
    void toolUpdateDeliveryFailureIsInfrastructureNotToolError() throws Exception {
        var updateReached = new CountDownLatch(1);
        var recorded = new CopyOnWriteArrayList<AgentEvent>();
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                recorded.add(event);
                if (event instanceof AgentEvent.ToolUpdate) {
                    updateReached.countDown();
                    return CompletableFuture.failedFuture(new RuntimeException("update sink boom"));
                }
                return CompletableFuture.completedStage(null);
            }
        };
        var tool = new AgentTool<Object>() {
            @Override public String name() { return "update_fail"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                updates.update(new Content.Text("partial")).toCompletableFuture().join();
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("done"))));
            }
        };
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "update_fail")), StopReason.TOOL_CALL),
                assistantText("never", StopReason.STOP));
        var ctx = contextWithTools(tool);
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, sink), source.signal()),
                executor);

        assertTrue(updateReached.await(2, TimeUnit.SECONDS), "update must reach the sink");
        var ex = assertThrows(ExecutionException.class, () -> fut.get(5, TimeUnit.SECONDS));
        assertTrue(containsMessage(ex, "update sink boom"),
                "update delivery failure must propagate as infrastructure failure");
        assertEquals(1, client.receivedRequests().size(),
                "no second model call after an infrastructure failure");
        assertFalse(recorded.stream().anyMatch(e -> e instanceof AgentEvent.ToolCompleted),
                "no ToolCompleted may be fabricated for a delivery failure");
    }

    @Test
    void interruptDuringDrainStillWaitsForAcceptedTasksAndRestoresFlag() throws Exception {
        var c1Blocked = new CountDownLatch(1);
        var releaseC1 = new CountDownLatch(1);
        var c1Finished = new CountDownLatch(1);
        var c2Recorded = new CountDownLatch(1);
        var recorded = new CopyOnWriteArrayList<AgentEvent>();
        var loopThread = new AtomicReference<Thread>();

        var b1 = new AgentTool<Object>() {
            @Override public String name() { return "b1"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                c1Blocked.countDown();
                try {
                    releaseC1.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                }
                c1Finished.countDown();
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("b1"))));
            }
        };
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                recorded.add(event);
                if (event instanceof AgentEvent.ToolCompleted tc && tc.result().toolCallId().equals("c2")) {
                    c2Recorded.countDown();
                }
                return CompletableFuture.completedStage(null);
            }
        };
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "b1"), toolCall("c2", "t2")), StopReason.TOOL_CALL),
                assistantText("never", StopReason.STOP));
        var ctx = contextWithTools(b1, quickTool("t2"));
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() -> {
            loopThread.set(Thread.currentThread());
            return loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, sink), source.signal());
        }, executor);

        try {
            assertTrue(c1Blocked.await(2, TimeUnit.SECONDS), "accepted task must be executing");
            // Positive gate: once the fast tool's ToolCompleted has been
            // recorded, the drain has consumed its completion and the loop
            // thread is (or is about to be) blocked in take() waiting for c1.
            // Interrupting there is observed deterministically by the next
            // interruptible take().
            assertTrue(c2Recorded.await(2, TimeUnit.SECONDS),
                    "the fast tool's completion must be recorded before the interrupt");
            // Positive evidence the loop thread is blocked in the completion
            // drain (ExecutorCompletionService.take -> LinkedBlockingQueue.take)
            // rather than merely between the c2 emit and the next take().
            awaitStack(loopThread.get(), "java.util.concurrent.LinkedBlockingQueue", "take", 5_000);
            loopThread.get().interrupt();
            assertFalse(fut.isDone(),
                    "run must not settle before every accepted task has been drained");
            releaseC1.countDown();
            assertTrue(c1Finished.await(2, TimeUnit.SECONDS),
                    "the accepted task must settle before the run ends");
            var ex = assertThrows(ExecutionException.class, () -> fut.get(5, TimeUnit.SECONDS));
            assertTrue(containsMessage(ex, "interrupted while draining tool tasks"),
                    "the drain interruption must propagate");
            assertTrue(loopThread.get().isInterrupted(), "interrupt flag must be restored on the run thread");
            assertFalse(recorded.stream().anyMatch(e -> e instanceof AgentEvent.ToolCompleted tc
                            && tc.result().toolCallId().equals("c1")),
                    "no lifecycle event may be emitted after the interrupt failure");
        } finally {
            releaseC1.countDown();
        }
    }

    // --- Slice 7b: accepted-update drain under interruption and failure ---

    @Test
    void interruptionDuringSettleWaitsForAcceptedUpdateAndRestoresFlag() throws Exception {
        var updateStage = new CompletableFuture<Void>();
        var updateReached = new CountDownLatch(1);
        var executeEntered = new CountDownLatch(1);
        var executeReturned = new CountDownLatch(1);
        var releaseExecute = new CountDownLatch(1);
        var updateSink = new AtomicReference<ToolUpdateSink>();
        var workerThread = new AtomicReference<Thread>();
        var recorded = new CopyOnWriteArrayList<AgentEvent>();

        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                recorded.add(event);
                if (event instanceof AgentEvent.ToolUpdate) {
                    updateReached.countDown();
                    return updateStage;
                }
                return CompletableFuture.completedStage(null);
            }
        };
        var tool = new AgentTool<Object>() {
            @Override public String name() { return "settle_interrupt"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                workerThread.set(Thread.currentThread());
                updateSink.set(updates);
                executeEntered.countDown();
                try {
                    releaseExecute.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                }
                executeReturned.countDown();
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("done"))));
            }
        };
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "settle_interrupt")), StopReason.TOOL_CALL),
                assistantText("never", StopReason.STOP));
        var ctx = contextWithTools(tool);
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, sink), source.signal()),
                executor);

        try {
            assertTrue(executeEntered.await(2, TimeUnit.SECONDS), "tool execute must start");
            var updateFuture = CompletableFuture.runAsync(() ->
                            updateSink.get().update(new Content.Text("partial")).toCompletableFuture().join(),
                    executor);
            assertTrue(updateReached.await(2, TimeUnit.SECONDS), "accepted update must reach the sink");
            releaseExecute.countDown();
            assertTrue(executeReturned.await(2, TimeUnit.SECONDS),
                    "execute must settle before the worker is interrupted");
            // Positive evidence the worker is blocked in settle() waiting for
            // the accepted update before interrupting it there.
            awaitStack(workerThread.get(), SINK_CLASS, "settle", 5_000);
            workerThread.get().interrupt();
            assertFalse(fut.isDone(),
                    "run must keep waiting for the accepted update even when interrupted");
            updateStage.complete(null);
            updateFuture.get(5, TimeUnit.SECONDS);
            var ex = assertThrows(ExecutionException.class, () -> fut.get(5, TimeUnit.SECONDS));
            assertTrue(containsMessage(ex, "interrupted while draining tool updates"),
                    "settle interruption must propagate");
            assertTrue(workerThread.get().isInterrupted(), "interrupt flag must be restored");
            assertEquals(1, client.receivedRequests().size(),
                    "no second model call after an infrastructure failure");
            assertFalse(recorded.stream().anyMatch(e -> e instanceof AgentEvent.ToolCompleted),
                    "no ToolCompleted may be emitted after settle interruption");
            // captured sink is closed: late update is dropped
            updateSink.get().update(new Content.Text("late")).toCompletableFuture().join();
            var updateCount = recorded.stream().filter(e -> e instanceof AgentEvent.ToolUpdate).count();
            assertEquals(1, updateCount, "late update after settlement must be dropped");
        } finally {
            releaseExecute.countDown();
            updateStage.complete(null);
        }
    }

    @Test
    void directDeliveryFailureDrainsAcceptedBlockedUpdateBeforeFailing() throws Exception {
        var firstUpdateReached = new CountDownLatch(1);
        var executeEntered = new CountDownLatch(1);
        var firstStage = new CompletableFuture<Void>();
        var updateSink = new AtomicReference<ToolUpdateSink>();
        var workerThread = new AtomicReference<Thread>();
        var recorded = new CopyOnWriteArrayList<AgentEvent>();

        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                recorded.add(event);
                if (event instanceof AgentEvent.ToolUpdate tu
                        && tu.update() instanceof Content.Text t) {
                    if (t.text().equals("first")) {
                        firstUpdateReached.countDown();
                        return firstStage; // accepted and blocked
                    }
                    return CompletableFuture.failedFuture(new RuntimeException("second boom"));
                }
                return CompletableFuture.completedStage(null);
            }
        };
        var tool = new AgentTool<Object>() {
            @Override public String name() { return "direct_fail"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                workerThread.set(Thread.currentThread());
                updateSink.set(updates);
                executeEntered.countDown();
                // Accept one blocking update from a helper thread first.
                CompletableFuture.runAsync(() ->
                        updates.update(new Content.Text("first")).toCompletableFuture().join(), executor);
                try {
                    firstUpdateReached.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                }
                // Synchronous failing update on the execute thread, NOT caught:
                // the EventDeliveryException escapes execute and must hit the
                // delivery-failure branch of executeAndFinalize().
                updates.update(new Content.Text("second")).toCompletableFuture().join();
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("done"))));
            }
        };
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "direct_fail")), StopReason.TOOL_CALL),
                assistantText("never", StopReason.STOP));
        var ctx = contextWithTools(tool);
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, sink), source.signal()),
                executor);

        try {
            assertTrue(executeEntered.await(2, TimeUnit.SECONDS), "tool execute must start");
            assertTrue(firstUpdateReached.await(2, TimeUnit.SECONDS), "blocking update must be accepted");
            // Positive evidence: the execute thread failed and is now draining
            // the blocked accepted update inside LoopToolUpdateSink.settle().
            awaitStack(workerThread.get(), SINK_CLASS, "settle", 5_000);
            assertFalse(fut.isDone(),
                    "run must drain the blocked accepted update before failing");
            firstStage.complete(null);
            var ex = assertThrows(ExecutionException.class, () -> fut.get(5, TimeUnit.SECONDS));
            assertTrue(containsMessage(ex, "second boom"), "delivery failure must propagate");
            assertEquals(1, client.receivedRequests().size(),
                    "no second model call after an infrastructure failure");
            assertFalse(recorded.stream().anyMatch(e -> e instanceof AgentEvent.ToolCompleted),
                    "no ToolCompleted may be emitted for a delivery failure");
            // captured sink is closed: late update is dropped
            updateSink.get().update(new Content.Text("late")).toCompletableFuture().join();
            var updateCount = recorded.stream().filter(e -> e instanceof AgentEvent.ToolUpdate).count();
            assertEquals(2, updateCount, "late update after settlement must be dropped");
        } finally {
            firstStage.complete(null);
        }
    }

    @Test
    void earliestAcceptedUpdateFailureWinsOverLaterEscapedExecuteFailure() throws Exception {
        var recorded = new CopyOnWriteArrayList<AgentEvent>();
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                recorded.add(event);
                if (event instanceof AgentEvent.ToolUpdate tu
                        && tu.update() instanceof Content.Text t) {
                    return CompletableFuture.failedFuture(new RuntimeException(t.text() + " boom"));
                }
                return CompletableFuture.completedStage(null);
            }
        };
        var tool = new AgentTool<Object>() {
            @Override public String name() { return "precedence"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                // First delivery failure is recorded by the sink and caught by
                // the tool; it is the earliest accepted-update failure.
                try {
                    updates.update(new Content.Text("first")).toCompletableFuture().join();
                } catch (RuntimeException ignored) {
                    // tool absorbs the delivery failure
                }
                // Second delivery failure escapes execute synchronously; it is
                // the LATER failure and must not override the first one.
                updates.update(new Content.Text("second")).toCompletableFuture().join();
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("done"))));
            }
        };
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "precedence")), StopReason.TOOL_CALL),
                assistantText("never", StopReason.STOP));
        var ctx = contextWithTools(tool);
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, sink), source.signal()),
                executor);

        var ex = assertThrows(ExecutionException.class, () -> fut.get(5, TimeUnit.SECONDS));
        assertTrue(containsMessage(ex, "first boom"),
                "the sink's earliest accepted-update failure must win over the later escaped failure");
        assertFalse(containsMessage(ex, "second boom"),
                "the later escaped failure must be suppressed in favor of the earlier one");
        assertEquals(1, client.receivedRequests().size(),
                "no second model call after an infrastructure failure");
        assertFalse(recorded.stream().anyMatch(e -> e instanceof AgentEvent.ToolCompleted),
                "no ToolCompleted may be emitted for a delivery failure");
    }

    // --- Slice 8: failure normalization matrix ---

    @Test
    void beforeHookSyncThrowIsImmediateFailure() {
        assertBeforeHookFailure((call, tool, args, ctx, c) -> {
            throw new RuntimeException("before sync boom");
        }, "before hook failed");
    }

    @Test
    void beforeHookFailedStageIsImmediateFailure() {
        assertBeforeHookFailure((call, tool, args, ctx, c) ->
                CompletableFuture.failedFuture(new RuntimeException("before stage boom")), "before hook failed");
    }

    @Test
    void beforeHookNullStageIsImmediateFailure() {
        assertBeforeHookFailure((call, tool, args, ctx, c) -> null, "before hook returned null stage");
    }

    @Test
    void beforeHookNullDecisionIsImmediateFailure() {
        assertBeforeHookFailure((call, tool, args, ctx, c) ->
                CompletableFuture.completedStage(null), "before hook returned null decision");
    }

    private void assertBeforeHookFailure(BeforeToolCall hook, String expectedPrefix) {
        var execCount = new AtomicInteger();
        var echo = TestTools.sideEffect("echo", execCount::incrementAndGet);
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL),
                assistantText("after", StopReason.STOP));
        var ctx = contextWithTools(echo);
        var run = runWithHooks(client, ctx, hook, AfterToolCall.noop());

        assertEquals(0, execCount.get(), "tool must not execute after a before-hook failure");
        var tc = run.sink().events().stream()
                .filter(e -> e instanceof AgentEvent.ToolCompleted)
                .map(e -> ((AgentEvent.ToolCompleted) e).result())
                .findFirst().orElseThrow();
        assertTrue(tc.error());
        var text = (Content.Text) tc.content().get(0);
        assertTrue(text.text().contains(expectedPrefix), "unexpected message: " + text.text());
        assertEquals(2, client.receivedRequests().size(), "run must continue after an error result");
    }

    @Test
    void schemaSyncThrowIsImmediateFailure() {
        var execCount = new AtomicInteger();
        var tool = new AgentTool<Object>() {
            @Override public String name() { return "schema_boom"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public JsonNode parametersSchema() {
                throw new RuntimeException("schema boom");
            }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                execCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("ok"))));
            }
        };
        // Drive the tool pipeline directly: a tool whose parametersSchema()
        // throws must fail prepare as an immediate error result, not as an
        // infrastructure failure. (The public Agent path would call spec() at
        // model-request time first, which is a separate concern.)
        var ctx = contextWithTools(tool);
        var state = new LoopState(ctx, MODEL, ThinkingLevel.PROVIDER_DEFAULT);
        var source = new CancellationSource();
        var exec = ToolCallExecutor.of(state, config(new ScriptedModelClient(), AgentEventSink.noop()),
                source.signal(), executor);
        var outcomes = exec.runBatch(List.of(toolCall("c1", "schema_boom")));

        assertEquals(0, execCount.get(), "tool must not execute when the schema throws");
        assertEquals(1, outcomes.size());
        assertTrue(outcomes.get(0).message().error(),
                "schema throw must become an immediate failure");
        var text = (Content.Text) outcomes.get(0).message().content().get(0);
        assertTrue(text.text().contains("schema validation failed"),
                "unexpected message: " + text.text());
    }

    @Test
    void executeSyncThrowIsErrorResult() {
        assertExecuteFailureIsErrorResult(new AgentTool<Object>() {
            @Override public String name() { return "sync_throw"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                throw new RuntimeException("execute sync boom");
            }
        });
    }

    @Test
    void executeFailedStageIsErrorResult() {
        assertExecuteFailureIsErrorResult(new AgentTool<Object>() {
            @Override public String name() { return "failed_stage"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                return CompletableFuture.failedFuture(new RuntimeException("execute stage boom"));
            }
        });
    }

    private void assertExecuteFailureIsErrorResult(AgentTool<Object> tool) {
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", tool.name())), StopReason.TOOL_CALL),
                assistantText("after", StopReason.STOP));
        var ctx = contextWithTools(tool);
        var run = run(client, ctx);

        var tc = run.sink().events().stream()
                .filter(e -> e instanceof AgentEvent.ToolCompleted)
                .map(e -> ((AgentEvent.ToolCompleted) e).result())
                .findFirst().orElseThrow();
        assertTrue(tc.error(), "execute failure must become an error result");
        var text = (Content.Text) tc.content().get(0);
        assertTrue(text.text().contains("tool execution failed"), "unexpected message: " + text.text());
        assertEquals(2, client.receivedRequests().size(), "run must continue after an error result");
    }

    @Test
    void executeNullStageIsErrorResult() {
        assertExecuteNullIsErrorResult(new AgentTool<Object>() {
            @Override public String name() { return "null_stage"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                return null;
            }
        });
    }

    @Test
    void executeNullResultIsErrorResult() {
        assertExecuteNullIsErrorResult(new AgentTool<Object>() {
            @Override public String name() { return "null_result"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                return CompletableFuture.completedFuture(null);
            }
        });
    }

    private void assertExecuteNullIsErrorResult(AgentTool<Object> tool) {
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", tool.name())), StopReason.TOOL_CALL),
                assistantText("after", StopReason.STOP));
        var ctx = contextWithTools(tool);
        var run = run(client, ctx);

        var tc = run.sink().events().stream()
                .filter(e -> e instanceof AgentEvent.ToolCompleted)
                .map(e -> ((AgentEvent.ToolCompleted) e).result())
                .findFirst().orElseThrow();
        assertTrue(tc.error(), "null execute result must become an error result");
        var text = (Content.Text) tc.content().get(0);
        assertTrue(text.text().contains("tool execution failed"), "unexpected message: " + text.text());
        assertEquals(2, client.receivedRequests().size(), "run must continue after an error result");
    }

    @Test
    void afterHookSyncThrowKeepsOriginalResult() {
        assertAfterHookKeepsOriginal((call, tool, result, ctx, c) -> {
            throw new RuntimeException("after sync boom");
        });
    }

    @Test
    void afterHookFailedStageKeepsOriginalResult() {
        assertAfterHookKeepsOriginal((call, tool, result, ctx, c) ->
                CompletableFuture.failedFuture(new RuntimeException("after stage boom")));
    }

    @Test
    void afterHookNullStageKeepsOriginalResult() {
        assertAfterHookKeepsOriginal((call, tool, result, ctx, c) -> null);
    }

    @Test
    void afterHookNullResultKeepsOriginalResult() {
        assertAfterHookKeepsOriginal((call, tool, result, ctx, c) ->
                CompletableFuture.completedStage(null));
    }

    private void assertAfterHookKeepsOriginal(AfterToolCall after) {
        var tool = quickTool("patched");
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "patched")), StopReason.TOOL_CALL),
                assistantText("after", StopReason.STOP));
        var ctx = contextWithTools(tool);
        var run = runWithHooks(client, ctx, BeforeToolCall.noop(), after);

        var tc = run.sink().events().stream()
                .filter(e -> e instanceof AgentEvent.ToolCompleted)
                .map(e -> ((AgentEvent.ToolCompleted) e).result())
                .findFirst().orElseThrow();
        assertFalse(tc.error(), "after hook failure must keep the execution result");
        var text = (Content.Text) tc.content().get(0);
        assertEquals("patched", text.text(), "after hook failure must keep the execution result");
    }

    // --- Slice 8: cancellation during prepare ---

    @Test
    void cancellationDuringPrepareSkipsRemainingCallsButSettlesStartedOnes() throws Exception {
        var beforeStarted = new CountDownLatch(1);
        var beforeRelease = new CountDownLatch(1);
        var execCount = new AtomicInteger();
        var echo = TestTools.sideEffect("echo", execCount::incrementAndGet);
        BeforeToolCall blockingBefore = (call, tool, args, ctx, c) -> {
            if (call.id().equals("c1")) {
                beforeStarted.countDown();
                try {
                    beforeRelease.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                }
            }
            return CompletableFuture.completedStage(new BeforeToolCall.Decision.Proceed());
        };
        var client = new ScriptedModelClient(
                assistant(List.of(toolCall("c1", "echo"), toolCall("c2", "echo")), StopReason.TOOL_CALL),
                assistantText("never", StopReason.STOP));
        var ctx = contextWithTools(echo);
        var recorder = new RecordingEventSink();
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx,
                        configWithHooks(client, recorder, blockingBefore, AfterToolCall.noop()), source.signal()),
                executor);

        try {
            assertTrue(beforeStarted.await(2, TimeUnit.SECONDS), "first prepare must start");
            source.cancel();
            beforeRelease.countDown();
            var result = awaitSettled(fut);

            assertEquals(1, execCount.get(), "started/prepared calls must still settle");
            assertEquals(List.of("c1"), toolCallIds(recorder.events()),
                    "later calls must not be started after cancellation");
            var last = (Message.Assistant) ((StandardAgentMessage) result.context().messages().getLast()).message();
            assertEquals(StopReason.ABORTED, last.stopReason(), "run must abort after the cancelled turn");
        } finally {
            beforeRelease.countDown();
        }
    }

    /**
     * Test-only {@link ExecutorService} that rejects tasks after
     * {@code rejectAfter} accepted submissions. Exercises the submit-rejection
     * state machine deterministically without shutting down a shared executor.
     * Extends {@link AbstractExecutorService} so every submit path funnels
     * through {@link #execute(Runnable)} and the rejection counter.
     */
    private static final class RejectingExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
        private final int rejectAfter;
        private final AtomicInteger accepted = new AtomicInteger();
        private final CountDownLatch rejectionObserved = new CountDownLatch(1);

        RejectingExecutor(int rejectAfter) {
            this.rejectAfter = rejectAfter;
        }

        CountDownLatch rejectionObserved() {
            return rejectionObserved;
        }

        @Override
        public void execute(Runnable command) {
            if (accepted.incrementAndGet() > rejectAfter) {
                rejectionObserved.countDown();
                throw new RejectedExecutionException("rejected by test executor");
            }
            delegate.execute(command);
        }

        @Override public void shutdown() { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
        @Override public void close() { delegate.close(); }
    }
}
