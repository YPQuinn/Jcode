package site.pplee.jcode.agentcore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.queue.PendingMessageQueue;
import site.pplee.jcode.agentcore.queue.PendingMessageSource;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.agentcore.support.RecordingEventSink;
import site.pplee.jcode.agentcore.support.TestTools;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;

import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {
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

    private static Content.ToolCall toolCall(String id, String name, JsonNode args) {
        return new Content.ToolCall(id, name, args);
    }

    private static Content.ToolCall toolCall(String id, String name) {
        return toolCall(id, name, MAPPER.getNodeFactory().textNode("hello"));
    }

    private static AgentContext contextWithTools(AgentTool<?>... tools) {
        var list = new ArrayList<AgentTool<?>>();
        for (var t : tools) {
            list.add(t);
        }
        return new AgentContext("sys", List.of(), List.copyOf(list));
    }

    private AgentLoopConfig config(ModelClient modelClient, AgentEventSink eventSink) {
        return new AgentLoopConfig(MODEL, modelClient, MAPPER, null, null, null, null, null, eventSink);
    }

    private AgentLoopConfig configWithSources(
            ModelClient modelClient, AgentEventSink eventSink,
            PendingMessageSource steering, PendingMessageSource followUp) {
        return new AgentLoopConfig(MODEL, modelClient, MAPPER, null, null, null, steering, followUp, eventSink);
    }

    private List<Message> projected(AgentMessage... msgs) {
        var out = new ArrayList<Message>();
        for (var m : msgs) {
            if (m instanceof StandardAgentMessage sam) {
                out.add(sam.message());
            }
        }
        return List.copyOf(out);
    }

    private LoopResult runPrompt(ModelClient client, AgentContext ctx, AgentEventSink sink) {
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        return loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, sink), source.signal());
    }

    private LoopResult runPrompt(ModelClient client, AgentContext ctx, AgentEventSink sink, CancellationSource source) {
        var loop = new AgentLoop(executor);
        return loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, sink), source.signal());
    }

    private LoopResult continueRun(ModelClient client, AgentContext ctx, AgentEventSink sink) {
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        return loop.continueRun(ctx, config(client, sink), source.signal());
    }

    // --- basic flow ---

    @Test
    void promptRunsSingleTurnWhenModelStopsImmediately() {
        var recorder = new RecordingEventSink();
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("hello", StopReason.STOP));
        var ctx = contextWithTools();
        var result = runPrompt(client, ctx, recorder);

        // messages: prompt + assistant
        assertEquals(2, result.context().messages().size());
        assertEquals(userMsg("hi"), result.context().messages().get(0));
        assertEquals(StandardAgentMessage.of(assistantText("hello", StopReason.STOP)),
                result.context().messages().get(1));
        assertEquals(2, result.newMessages().size());

        // single model call; request carried projected prompt + system + empty tools
        assertEquals(1, client.receivedRequests().size());
        var req = client.receivedRequests().get(0);
        assertEquals(MODEL, req.model());
        assertEquals("sys", req.systemPrompt());
        assertEquals(projected(userMsg("hi")), req.messages());
        assertTrue(req.tools().isEmpty());

        // event sequence: AgentStarted, TurnStarted, MessageStarted(prompt),
        // MessageCompleted(prompt), MessageStarted(assistant), MessageCompleted(assistant),
        // TurnCompleted, AgentCompleted
        var events = recorder.events();
        assertInstanceOf(AgentEvent.AgentStarted.class, events.get(0));
        assertInstanceOf(AgentEvent.TurnStarted.class, events.get(1));
        assertInstanceOf(AgentEvent.MessageStarted.class, events.get(2));
        assertInstanceOf(AgentEvent.MessageCompleted.class, events.get(3));
        assertInstanceOf(AgentEvent.MessageStarted.class, events.get(4));
        assertInstanceOf(AgentEvent.MessageCompleted.class, events.get(5));
        assertInstanceOf(AgentEvent.TurnCompleted.class, events.get(6));
        assertInstanceOf(AgentEvent.AgentCompleted.class, events.get(7));
        assertEquals(8, events.size());
    }

    @Test
    void promptExecutesSingleToolCallThenStops() {
        var recorder = new RecordingEventSink();
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistant(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL),
                assistantText("after", StopReason.STOP));
        var ctx = contextWithTools(TestTools.echo());
        var result = runPrompt(client, ctx, recorder);

        // prompt + assistant(toolcall) + toolresult + assistant(stop)
        assertEquals(4, result.context().messages().size());
        assertEquals(2, client.receivedRequests().size());
        // 2nd request carried the echo tool spec
        var secondReq = client.receivedRequests().get(1);
        assertEquals(1, secondReq.tools().size());
        assertEquals("echo", secondReq.tools().get(0).name());

        var events = recorder.events();
        // AgentStarted, TurnStarted, MsgStarted(prompt), MsgCompleted(prompt),
        // MsgStarted(assistant), MsgCompleted(assistant),
        // ToolStarted, ToolCompleted, MsgStarted(toolresult), MsgCompleted(toolresult),
        // TurnCompleted, TurnStarted, MsgStarted(assistant), MsgCompleted(assistant),
        // TurnCompleted, AgentCompleted
        assertInstanceOf(AgentEvent.ToolStarted.class, events.get(6));
        assertInstanceOf(AgentEvent.ToolCompleted.class, events.get(7));
        assertInstanceOf(AgentEvent.MessageCompleted.class, events.get(9));
        assertInstanceOf(AgentEvent.TurnCompleted.class, events.get(10));
        assertInstanceOf(AgentEvent.TurnStarted.class, events.get(11));
    }

    @Test
    void continueRunResumesFromExistingContext() {
        var recorder = new RecordingEventSink();
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("resumed", StopReason.STOP));
        var existing = new AgentContext("sys",
                List.of(userMsg("a"), StandardAgentMessage.of(assistantText("prev", StopReason.STOP))),
                List.of());
        var result = continueRun(client, existing, recorder);

        // existing 2 + assistant = 3
        assertEquals(3, result.context().messages().size());
        assertEquals(StandardAgentMessage.of(assistantText("resumed", StopReason.STOP)),
                result.context().messages().get(2));
        // request projected the 2 existing messages (prompt user only)
        assertEquals(projected(userMsg("a"),
                StandardAgentMessage.of(assistantText("prev", StopReason.STOP))),
                client.receivedRequests().get(0).messages());
    }

    // --- terminal failures ---

    @Test
    void errorStopReasonEndsRunImmediately() {
        var recorder = new RecordingEventSink();
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                new Message.Assistant(List.of(new Content.Text("err")),
                        StopReason.ERROR, "boom", Usage.zero(), T1));
        var ctx = contextWithTools();
        var result = runPrompt(client, ctx, recorder);

        assertEquals(2, result.context().messages().size());
        assertEquals(1, client.receivedRequests().size());
        // No follow-up: single TurnCompleted then AgentCompleted
        var turnCompleteds = recorder.events().stream()
                .filter(e -> e instanceof AgentEvent.TurnCompleted).count();
        assertEquals(1, turnCompleteds);
    }

    @Test
    void modelFailureProducesErrorAssistantWhenNotCancelled() {
        var recorder = new RecordingEventSink();
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(); // empty queue
        var ctx = contextWithTools();
        var result = runPrompt(client, ctx, recorder);

        // prompt + error assistant
        assertEquals(2, result.context().messages().size());
        var last = ((StandardAgentMessage) result.context().messages().get(1)).message();
        assertInstanceOf(Message.Assistant.class, last);
        assertEquals(StopReason.ERROR, ((Message.Assistant) last).stopReason());
    }

    // --- LENGTH ---

    @Test
    void lengthFailsAllToolCallsWithoutExecuting() {
        var recorder = new RecordingEventSink();
        var execCount = new AtomicInteger();
        var countingEcho = TestTools.sideEffect("echo", execCount::incrementAndGet);
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistant(List.of(toolCall("c1", "echo"), toolCall("c2", "echo")),
                        StopReason.LENGTH),
                // after the LENGTH failures the loop continues (results are
                // non-terminating) and the model stops cleanly
                assistantText("done", StopReason.STOP));
        var ctx = contextWithTools(countingEcho);
        var result = runPrompt(client, ctx, recorder);

        // tools NOT executed
        assertEquals(0, execCount.get());
        // prompt + assistant(LENGTH) + 2 failed tool results + assistant(STOP)
        assertEquals(5, result.context().messages().size());
        // both tool results are errors
        var toolResults = recorder.events().stream()
                .filter(e -> e instanceof AgentEvent.ToolCompleted tc)
                .map(e -> ((AgentEvent.ToolCompleted) e).result())
                .toList();
        assertEquals(2, toolResults.size());
        assertTrue(toolResults.get(0).error());
        assertTrue(toolResults.get(1).error());
    }

    // --- sequential vs parallel ---

    @Test
    void parallelExecutionRunsToolCallsConcurrently() throws Exception {
        var recorder = new RecordingEventSink();
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var b1 = TestTools.blocking("b1", started, release, ToolExecutionMode.PARALLEL);
        var b2 = TestTools.blocking("b2", started, release, ToolExecutionMode.PARALLEL);
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistant(List.of(toolCall("c1", "b1"), toolCall("c2", "b2")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        var ctx = contextWithTools(b1, b2);

        // run in a separate thread so we can release after both started
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, recorder), source.signal()),
                executor);
        // both started -> parallel
        assertTrue(started.await(2, TimeUnit.SECONDS), "both tools should start concurrently");
        release.countDown();
        var result = fut.join();

        // prompt + assistant(toolcall) + tr1 + tr2 + assistant(stop)
        assertEquals(5, result.context().messages().size());
    }

    @Test
    void sequentialExecutionRunsToolCallsNonOverlapping() throws Exception {
        var recorder = new RecordingEventSink();
        var firstStarted = new CountDownLatch(1);
        var firstRelease = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondRelease = new CountDownLatch(1);
        // first tool blocks; second tool's "started" latch should NOT count down
        // until the first is released -> proves non-overlap.
        var b1 = TestTools.blocking("b1", firstStarted, firstRelease, ToolExecutionMode.SEQUENTIAL);
        var b2 = TestTools.blocking("b2", secondStarted, secondRelease, ToolExecutionMode.SEQUENTIAL);
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistant(List.of(toolCall("c1", "b1"), toolCall("c2", "b2")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        var ctx = contextWithTools(b1, b2);

        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, recorder), source.signal()),
                executor);

        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        assertFalse(secondStarted.await(200, TimeUnit.MILLISECONDS),
                "second tool must not start before first finishes");
        firstRelease.countDown();
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
        secondRelease.countDown();
        var result = fut.join();

        // prompt + assistant(toolcall) + tr1 + tr2 + assistant(stop)
        assertEquals(5, result.context().messages().size());
    }

    @Test
    void sequentialModeForcesBatchSequentialEvenIfToolsAreParallel() throws Exception {
        var recorder = new RecordingEventSink();
        var firstStarted = new CountDownLatch(1);
        var firstRelease = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondRelease = new CountDownLatch(1);
        var b1 = TestTools.blocking("b1", firstStarted, firstRelease, ToolExecutionMode.PARALLEL);
        var b2 = TestTools.blocking("b2", secondStarted, secondRelease, ToolExecutionMode.PARALLEL);
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistant(List.of(toolCall("c1", "b1"), toolCall("c2", "b2")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        var ctx = contextWithTools(b1, b2);

        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var cfg = new AgentLoopConfig(MODEL, client, MAPPER, ToolExecutionMode.SEQUENTIAL,
                null, null, null, null, recorder);
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx, cfg, source.signal()), executor);

        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        assertFalse(secondStarted.await(200, TimeUnit.MILLISECONDS));
        firstRelease.countDown();
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
        secondRelease.countDown();
        fut.join();
    }

    // --- cancellation ---

    @Test
    void cancellationBeforeModelCallProducesAbortedAssistant() {
        var recorder = new RecordingEventSink();
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("never", StopReason.STOP));
        var ctx = contextWithTools();
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        source.cancel(); // pre-cancel
        var result = loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, recorder), source.signal());

        // prompt + aborted assistant
        assertEquals(2, result.context().messages().size());
        var last = ((StandardAgentMessage) result.context().messages().get(1)).message();
        assertEquals(StopReason.ABORTED, ((Message.Assistant) last).stopReason());
        // model never called
        assertEquals(0, client.receivedRequests().size());
    }

    @Test
    void cancellationBeforeToolBatchProducesAbortedAssistant() {
        var recorder = new RecordingEventSink();
        var execCount = new AtomicInteger();
        var cancelSource = new CancellationSource();
        // a tool that cancels on execute and terminates
        var cancelTool = TestTools.cancelAndTerminate("canceller", cancelSource);
        var echo = TestTools.sideEffect("echo", execCount::incrementAndGet);
        // batch: [canceller(sequential, cancels+terminates), echo]
        // first tool runs, cancels, terminates -> allTerminated true -> inner exits
        // post-inner-loop cancellation check fires -> ABORTED
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistant(List.of(toolCall("c1", "canceller"), toolCall("c2", "echo")),
                        StopReason.TOOL_CALL),
                assistantText("never", StopReason.STOP));
        var ctx = contextWithTools(cancelTool, echo);
        var loop = new AgentLoop(executor);
        var result = loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, recorder), cancelSource.signal());

        // echo NOT executed (batch sequential, cancelled after first)
        assertEquals(0, execCount.get());
        // final message is ABORTED
        var last = ((StandardAgentMessage) result.context().messages().get(result.context().messages().size() - 1)).message();
        assertEquals(StopReason.ABORTED, ((Message.Assistant) last).stopReason());
    }

    @Test
    void cancellationPropagatesToRunningToolSignal() throws Exception {
        var recorder = new RecordingEventSink();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var recorderFlag = new AtomicBoolean(false);
        var blocking = TestTools.recordingBlocking("block", started, release, recorderFlag);
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistant(List.of(toolCall("c1", "block")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        var ctx = contextWithTools(blocking);
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var fut = CompletableFuture.supplyAsync(() ->
                loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, recorder), source.signal()),
                executor);
        assertTrue(started.await(2, TimeUnit.SECONDS));
        source.cancel();
        release.countDown();
        var result = fut.join();

        assertTrue(recorderFlag.get(), "tool should observe cancelled signal");
    }

    // --- steering & follow-up ---

    @Test
    void steeringMessageInjectedBeforeNextModelCall() throws Exception {
        var recorder = new RecordingEventSink();
        var steering = new PendingMessageQueue(QueueMode.ALL);
        // A tool that enqueues the steering message during execute, so it lands
        // after the 1st model call but before the steering drain at the top of
        // the next iteration (before the 2nd model call).
        var echoSteering = new AgentTool<String>() {
            @Override public String name() { return "echo"; }
            @Override public Class<String> argumentType() { return String.class; }
            @Override public java.util.concurrent.CompletionStage<site.pplee.jcode.agentcore.tool.ToolExecutionResult> execute(
                    String id, String args, site.pplee.jcode.agentcore.tool.ToolUpdateSink updates, site.pplee.jcode.ai.concurrent.CancellationSignal c) {
                steering.enqueue(userMsg("steer"));
                return java.util.concurrent.CompletableFuture.completedFuture(
                        site.pplee.jcode.agentcore.tool.ToolExecutionResult.success(
                                List.of(new Content.Text(args))));
            }
        };
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistant(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL),
                assistantText("final", StopReason.STOP));
        var ctx = contextWithTools(echoSteering);
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var cfg = configWithSources(client, recorder, steering, null);
        var result = loop.runPrompt(List.of(userMsg("hi")), ctx, cfg, source.signal());

        // 2nd model call should have projected: prompt, assistant(toolcall),
        // toolresult, steer
        var secondReq = client.receivedRequests().get(1);
        assertEquals(projected(
                userMsg("hi"),
                StandardAgentMessage.of(assistant(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL))),
                secondReq.messages().subList(0, 2));
        // 3rd message is the tool result; 4th is the steer
        assertEquals(4, secondReq.messages().size());
        assertInstanceOf(Message.User.class, secondReq.messages().get(3));
        assertEquals("steer", ((Content.Text) ((Message.User) secondReq.messages().get(3)).content().get(0)).text());
    }

    @Test
    void followUpMessageKeepsRunGoingWhenModelWouldStop() {
        var recorder = new RecordingEventSink();
        var followUp = new PendingMessageQueue(QueueMode.ALL);
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("first", StopReason.STOP),
                assistantText("second", StopReason.STOP));
        var ctx = contextWithTools();
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        followUp.enqueue(userMsg("again"));
        var cfg = configWithSources(client, recorder, null, followUp);
        var result = loop.runPrompt(List.of(userMsg("hi")), ctx, cfg, source.signal());

        // prompt + assistant1 + followup + assistant2
        assertEquals(4, result.context().messages().size());
        assertEquals(StandardAgentMessage.of(assistantText("second", StopReason.STOP)),
                result.context().messages().get(3));
        assertEquals(2, client.receivedRequests().size());
    }

    @Test
    void terminatingToolStopsTheRun() {
        var recorder = new RecordingEventSink();
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistant(List.of(toolCall("c1", "terminating")), StopReason.TOOL_CALL),
                assistantText("never", StopReason.STOP));
        var ctx = contextWithTools(TestTools.terminating());
        var result = runPrompt(client, ctx, recorder);

        // prompt + assistant + terminating toolresult; NO 2nd model call
        assertEquals(3, result.context().messages().size());
        assertEquals(1, client.receivedRequests().size());
    }

    @Test
    void toolFailureProducesErrorToolResultButRunContinues() {
        var recorder = new RecordingEventSink();
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistant(List.of(toolCall("c1", "failing")), StopReason.TOOL_CALL),
                assistantText("after", StopReason.STOP));
        var ctx = contextWithTools(TestTools.failing());
        var result = runPrompt(client, ctx, recorder);

        // tool result is an error
        var toolResult = recorder.events().stream()
                .filter(e -> e instanceof AgentEvent.ToolCompleted)
                .map(e -> ((AgentEvent.ToolCompleted) e).result())
                .findFirst().orElseThrow();
        assertTrue(toolResult.error());
        // run continued to a 2nd model call
        assertEquals(2, client.receivedRequests().size());
    }

    @Test
    void unknownToolNameProducesErrorToolResult() {
        var recorder = new RecordingEventSink();
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistant(List.of(toolCall("c1", "ghost")), StopReason.TOOL_CALL),
                assistantText("after", StopReason.STOP));
        var ctx = contextWithTools(); // no tools
        var result = runPrompt(client, ctx, recorder);

        var toolResult = recorder.events().stream()
                .filter(e -> e instanceof AgentEvent.ToolCompleted)
                .map(e -> ((AgentEvent.ToolCompleted) e).result())
                .findFirst().orElseThrow();
        assertTrue(toolResult.error());
        assertEquals(2, client.receivedRequests().size());
    }

    @Test
    void contextRetainsPriorMessagesAcrossTurns() {
        var recorder = new RecordingEventSink();
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistant(List.of(toolCall("c1", "echo")), StopReason.TOOL_CALL),
                assistantText("done", StopReason.STOP));
        var ctx = contextWithTools(TestTools.echo());
        var result = runPrompt(client, ctx, recorder);

        // 2nd request projected prompt + assistant(toolcall) + toolresult
        var secondReq = client.receivedRequests().get(1);
        assertEquals(3, secondReq.messages().size());
    }
}
