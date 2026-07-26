package site.pplee.jcode.agentcore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.model.AgentContext;
import site.pplee.jcode.agentcore.model.AgentMessage;
import site.pplee.jcode.agentcore.model.Content;
import site.pplee.jcode.agentcore.model.LoopResult;
import site.pplee.jcode.agentcore.model.ModelRef;
import site.pplee.jcode.agentcore.model.StopReason;
import site.pplee.jcode.agentcore.model.ToolExecutionMode;
import site.pplee.jcode.agentcore.queue.PendingMessageQueue;
import site.pplee.jcode.agentcore.queue.PendingMessageSource;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.agentcore.spi.AgentTool;
import site.pplee.jcode.agentcore.spi.LlmClient;
import site.pplee.jcode.agentcore.support.RecordingEventSink;
import site.pplee.jcode.agentcore.support.ScriptedLlmClient;
import site.pplee.jcode.agentcore.support.TestTools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef MODEL = new ModelRef("test", "test-model");
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

    private static AgentMessage.User user(String text) {
        return new AgentMessage.User(List.of(new Content.Text(text)), T1);
    }

    private static AgentMessage.Assistant assistant(List<Content> content, StopReason reason) {
        return new AgentMessage.Assistant(content, reason, null, T1);
    }

    private static Content.ToolCall toolCall(String id, String name, JsonNode args) {
        return new Content.ToolCall(id, name, args);
    }

    private static AgentContext contextWithTools(AgentTool<?>... tools) {
        var list = new ArrayList<AgentTool<?>>();
        for (var t : tools) {
            list.add(t);
        }
        return new AgentContext("sys", List.of(), List.copyOf(list));
    }

    private AgentLoopConfig config(LlmClient llmClient, AgentEventSink eventSink) {
        return new AgentLoopConfig(MODEL, llmClient, MAPPER, null, null, null, eventSink, null);
    }

    private AgentLoopConfig configWithSources(
            LlmClient llmClient, AgentEventSink eventSink,
            PendingMessageSource steering, PendingMessageSource followUp) {
        return new AgentLoopConfig(MODEL, llmClient, MAPPER, null, steering, followUp, eventSink, null);
    }

    private static List<AgentMessage.ToolResult> toolResultsIn(LoopResult result) {
        return result.context().messages().stream()
                .filter(m -> m instanceof AgentMessage.ToolResult)
                .map(m -> (AgentMessage.ToolResult) m)
                .toList();
    }

    // --- case 1: single model answer ends loop ---

    @Test
    void singleModelAnswerEndsLoop() {
        var assistant = assistant(List.of(new Content.Text("hello")), StopReason.STOP);
        var llmClient = new ScriptedLlmClient(assistant);
        var eventSink = new RecordingEventSink();
        var config = config(llmClient, eventSink);
        var context = new AgentContext("sys", List.of(), List.of());
        var prompt = user("hi");
        var loop = new AgentLoop(executor);

        LoopResult result = loop.runPrompt(List.of(prompt), context, config, new CancellationSource().token());

        assertEquals(1, llmClient.receivedRequests().size());
        var messages = result.context().messages();
        assertEquals(2, messages.size());
        assertSame(prompt, messages.get(0));
        assertSame(assistant, messages.get(1));
        assertEquals(2, result.newMessages().size());

        var events = eventSink.events();
        assertEquals(6, events.size());
        assertInstanceOf(AgentEvent.AgentStarted.class, events.get(0));
        assertInstanceOf(AgentEvent.TurnStarted.class, events.get(1));
        assertSame(prompt, ((AgentEvent.MessageCompleted) events.get(2)).message());
        assertSame(assistant, ((AgentEvent.MessageCompleted) events.get(3)).message());
        var tc = (AgentEvent.TurnCompleted) events.get(4);
        assertSame(assistant, tc.assistant());
        assertEquals(List.of(), tc.toolResults());
        assertSame(result, ((AgentEvent.AgentCompleted) events.get(5)).result());
    }

    // --- case 2: single tool call executes and re-calls model ---

    @Test
    void singleToolCallExecutesAndReCallsModel() {
        var echo = TestTools.echo();
        var assistant1 = assistant(
                List.of(toolCall("c1", "echo", MAPPER.valueToTree("echo this"))),
                StopReason.TOOL_CALL);
        var assistant2 = assistant(List.of(new Content.Text("done")), StopReason.STOP);
        var llmClient = new ScriptedLlmClient(assistant1, assistant2);
        var eventSink = new RecordingEventSink();
        var config = config(llmClient, eventSink);
        var context = contextWithTools(echo);
        var loop = new AgentLoop(executor);

        var result = loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token());

        assertEquals(2, llmClient.receivedRequests().size());
        var messages = result.context().messages();
        assertEquals(4, messages.size());
        var tr = (AgentMessage.ToolResult) messages.get(2);
        assertEquals("echo", tr.toolName());
        assertFalse(tr.error());
        assertEquals("echo this", ((Content.Text) tr.content().get(0)).text());
        assertTrue(eventSink.events().stream().anyMatch(e -> e instanceof AgentEvent.ToolStarted));
        assertTrue(eventSink.events().stream().anyMatch(e -> e instanceof AgentEvent.ToolCompleted));
    }

    // --- case 3: parallel tools start concurrently, write back in source order ---

    @Test
    void parallelToolsStartConcurrentlyAndWriteBackInSourceOrder() throws Exception {
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var b1 = TestTools.blocking("b1", started, release, ToolExecutionMode.PARALLEL);
        var b2 = TestTools.blocking("b2", started, release, ToolExecutionMode.PARALLEL);
        var assistant1 = assistant(List.of(
                toolCall("c1", "b1", MAPPER.createObjectNode()),
                toolCall("c2", "b2", MAPPER.createObjectNode())), StopReason.TOOL_CALL);
        var assistant2 = assistant(List.of(new Content.Text("done")), StopReason.STOP);
        var llmClient = new ScriptedLlmClient(assistant1, assistant2);
        var config = config(llmClient, new RecordingEventSink());
        var context = contextWithTools(b1, b2);
        var loop = new AgentLoop(executor);

        var future = CompletableFuture.supplyAsync(
                () -> loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token()),
                executor);
        assertTrue(started.await(2, TimeUnit.SECONDS));
        release.countDown();
        var result = future.get(5, TimeUnit.SECONDS);

        assertEquals(2, llmClient.receivedRequests().size());
        var trs = toolResultsIn(result);
        assertEquals(2, trs.size());
        assertEquals("b1", trs.get(0).toolName());
        assertEquals("b2", trs.get(1).toolName());
    }

    // --- case 4: a sequential tool forces the whole batch to run one at a time ---

    @Test
    void sequentialBatchRunsToolsOneAtATime() throws Exception {
        var started1 = new CountDownLatch(1);
        var release1 = new CountDownLatch(1);
        var started2 = new CountDownLatch(1);
        var release2 = new CountDownLatch(1);
        var b1 = TestTools.blocking("b1", started1, release1, ToolExecutionMode.SEQUENTIAL);
        var b2 = TestTools.blocking("b2", started2, release2, ToolExecutionMode.PARALLEL);
        var assistant1 = assistant(List.of(
                toolCall("c1", "b1", MAPPER.createObjectNode()),
                toolCall("c2", "b2", MAPPER.createObjectNode())), StopReason.TOOL_CALL);
        var assistant2 = assistant(List.of(new Content.Text("done")), StopReason.STOP);
        var llmClient = new ScriptedLlmClient(assistant1, assistant2);
        var config = config(llmClient, new RecordingEventSink());
        var context = contextWithTools(b1, b2);
        var loop = new AgentLoop(executor);

        var future = CompletableFuture.supplyAsync(
                () -> loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token()),
                executor);
        assertTrue(started1.await(2, TimeUnit.SECONDS));
        assertEquals(1, started2.getCount());
        release1.countDown();
        assertTrue(started2.await(2, TimeUnit.SECONDS));
        release2.countDown();
        var result = future.get(5, TimeUnit.SECONDS);

        var trs = toolResultsIn(result);
        assertEquals(2, trs.size());
        assertEquals("b1", trs.get(0).toolName());
        assertEquals("b2", trs.get(1).toolName());
    }

    // --- case 5: tool errors produce error results and the loop continues ---

    @Test
    void toolErrorsProduceErrorResultsAndLoopContinues() {
        var echo = TestTools.echo();
        var failing = TestTools.failing();
        var assistant1 = assistant(List.of(
                toolCall("c1", "unknown", MAPPER.createObjectNode()),
                toolCall("c2", "echo", MAPPER.valueToTree(Map.of("x", 1))),
                toolCall("c3", "failing", MAPPER.createObjectNode())), StopReason.TOOL_CALL);
        var assistant2 = assistant(List.of(new Content.Text("recovered")), StopReason.STOP);
        var llmClient = new ScriptedLlmClient(assistant1, assistant2);
        var config = config(llmClient, new RecordingEventSink());
        var context = contextWithTools(echo, failing);
        var loop = new AgentLoop(executor);

        var result = loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token());

        assertEquals(2, llmClient.receivedRequests().size());
        var trs = toolResultsIn(result);
        assertEquals(3, trs.size());
        for (var tr : trs) {
            assertTrue(tr.error());
        }
        assertEquals("unknown", trs.get(0).toolName());
        assertEquals("echo", trs.get(1).toolName());
        assertEquals("failing", trs.get(2).toolName());
    }

    // --- case 6: LENGTH stop reason fails tool calls without executing ---

    @Test
    void lengthStopReasonFailsToolCallsWithoutExecution() {
        var echo = TestTools.echo();
        var assistant1 = assistant(
                List.of(toolCall("c1", "echo", MAPPER.valueToTree("hello"))),
                StopReason.LENGTH);
        var assistant2 = assistant(List.of(new Content.Text("recovered")), StopReason.STOP);
        var llmClient = new ScriptedLlmClient(assistant1, assistant2);
        var config = config(llmClient, new RecordingEventSink());
        var context = contextWithTools(echo);
        var loop = new AgentLoop(executor);

        var result = loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token());

        assertEquals(2, llmClient.receivedRequests().size());
        var trs = toolResultsIn(result);
        assertEquals(1, trs.size());
        assertTrue(trs.get(0).error());
        var text = (Content.Text) trs.get(0).content().get(0);
        assertTrue(text.text().contains("truncated"));
    }

    // --- case 7: steering injected after current turn, before next model call ---

    @Test
    void steeringInjectedAfterCurrentTurnBeforeNextModelCall() {
        var steeringQueue = new PendingMessageQueue(QueueMode.ALL);
        var steeringMsg = user("stop, do this instead");
        var enqueueTool = TestTools.sideEffect("enqueue", () -> steeringQueue.enqueue(steeringMsg));
        var assistant1 = assistant(
                List.of(toolCall("c1", "enqueue", MAPPER.createObjectNode())), StopReason.TOOL_CALL);
        var assistant2 = assistant(List.of(new Content.Text("done")), StopReason.STOP);
        var llmClient = new ScriptedLlmClient(assistant1, assistant2);
        var eventSink = new RecordingEventSink();
        var config = configWithSources(llmClient, eventSink, steeringQueue, null);
        var context = contextWithTools(enqueueTool);
        var loop = new AgentLoop(executor);

        var result = loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token());

        assertEquals(2, llmClient.receivedRequests().size());
        var messages = result.context().messages();
        assertEquals(5, messages.size()); // [user, assistant1, toolResult, steeringMsg, assistant2]
        assertSame(steeringMsg, messages.get(3));
        // Strengthened: the 2nd model request carried the steering message.
        assertTrue(llmClient.receivedRequests().get(1).messages().contains(steeringMsg));
    }

    // --- case 8: follow-up triggers next model call when idle ---

    @Test
    void followUpTriggersNextModelCallWhenIdle() {
        var followUpQueue = new PendingMessageQueue(QueueMode.ALL);
        var followUpMsg = user("also summarize");
        followUpQueue.enqueue(followUpMsg);
        var assistant1 = assistant(List.of(new Content.Text("first")), StopReason.STOP);
        var assistant2 = assistant(List.of(new Content.Text("second")), StopReason.STOP);
        var llmClient = new ScriptedLlmClient(assistant1, assistant2);
        var eventSink = new RecordingEventSink();
        var config = configWithSources(llmClient, eventSink, null, followUpQueue);
        var context = new AgentContext("sys", List.of(), List.of());
        var loop = new AgentLoop(executor);

        var result = loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token());

        assertEquals(2, llmClient.receivedRequests().size());
        var messages = result.context().messages();
        assertEquals(4, messages.size()); // [user, assistant1, followUpMsg, assistant2]
        assertSame(followUpMsg, messages.get(2));
        assertTrue(llmClient.receivedRequests().get(1).messages().contains(followUpMsg));
    }

    // --- case 8b: follow-up deferred behind tool work ---

    @Test
    void followUpDeferredBehindToolWork() {
        var followUpQueue = new PendingMessageQueue(QueueMode.ALL);
        var followUpMsg = user("also summarize");
        followUpQueue.enqueue(followUpMsg);
        var echo = TestTools.echo();
        var assistant1 = assistant(
                List.of(toolCall("c1", "echo", MAPPER.valueToTree("hi"))), StopReason.TOOL_CALL);
        var assistant2 = assistant(List.of(new Content.Text("done")), StopReason.STOP);
        var assistant3 = assistant(List.of(new Content.Text("final")), StopReason.STOP);
        var llmClient = new ScriptedLlmClient(assistant1, assistant2, assistant3);
        var eventSink = new RecordingEventSink();
        var config = configWithSources(llmClient, eventSink, null, followUpQueue);
        var context = contextWithTools(echo);
        var loop = new AgentLoop(executor);

        var result = loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token());

        assertEquals(3, llmClient.receivedRequests().size());
        // Request 2 (after the tool turn) does NOT carry follow-up: still queued.
        assertFalse(llmClient.receivedRequests().get(1).messages().contains(followUpMsg));
        // Request 3 (after STOP, follow-up drained) carries follow-up.
        assertTrue(llmClient.receivedRequests().get(2).messages().contains(followUpMsg));
    }

    // --- case 9: no follow-up emits AgentCompleted exactly once ---

    @Test
    void noFollowUpEmitsAgentCompletedOnce() {
        var followUpQueue = new PendingMessageQueue(QueueMode.ALL); // empty
        var assistant1 = assistant(List.of(new Content.Text("done")), StopReason.STOP);
        var llmClient = new ScriptedLlmClient(assistant1);
        var eventSink = new RecordingEventSink();
        var config = configWithSources(llmClient, eventSink, null, followUpQueue);
        var context = new AgentContext("sys", List.of(), List.of());
        var loop = new AgentLoop(executor);

        var result = loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token());

        assertEquals(1, llmClient.receivedRequests().size());
        assertEquals(2, result.context().messages().size()); // [user, assistant1]
        var completed = eventSink.events().stream()
                .filter(e -> e instanceof AgentEvent.AgentCompleted)
                .toList();
        assertEquals(1, completed.size());
        // Strengthened: sole AgentCompleted references the returned result and is the last event.
        assertSame(result, ((AgentEvent.AgentCompleted) completed.get(0)).result());
        assertSame(completed.get(0), eventSink.events().get(eventSink.events().size() - 1));
    }

    // --- case 10: cancellation before tool batch produces ABORTED, no tools ---

    @Test
    void cancellationBeforeToolBatchProducesAbortedAndNoTools() {
        var source = new CancellationSource();
        var echo = TestTools.echo();
        var assistant1 = assistant(List.of(
                toolCall("c1", "echo", MAPPER.valueToTree("a")),
                toolCall("c2", "echo", MAPPER.valueToTree("b"))), StopReason.TOOL_CALL);
        var generateCount = new AtomicInteger(0);
        LlmClient cancellingClient = (request, cancellation, events) -> {
            generateCount.incrementAndGet();
            source.cancel();
            return CompletableFuture.completedFuture(assistant1);
        };
        var eventSink = new RecordingEventSink();
        var config = config(cancellingClient, eventSink);
        var context = contextWithTools(echo);
        var loop = new AgentLoop(executor);

        var result = loop.runPrompt(List.of(user("hi")), context, config, source.token());

        assertEquals(1, generateCount.get());
        var messages = result.context().messages();
        assertEquals(3, messages.size()); // [user, assistant1, aborted]
        var aborted = (AgentMessage.Assistant) messages.get(2);
        assertEquals(StopReason.ABORTED, aborted.stopReason());
        // Strengthened: no tools started, completed, or resulted.
        assertTrue(messages.stream().noneMatch(m -> m instanceof AgentMessage.ToolResult));
        var events = eventSink.events();
        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.ToolStarted));
        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.ToolCompleted));
    }

    // --- case 10b: mid-batch cancellation produces ABORTED (post-inner-loop check) ---

    @Test
    void cancellationMidBatchProducesAborted() {
        var source = new CancellationSource();
        var cancelTool = TestTools.cancelAndTerminate("cancel", source);
        var echo = TestTools.echo(); // must NOT run
        var assistant1 = assistant(List.of(
                toolCall("c1", "cancel", MAPPER.createObjectNode()),
                toolCall("c2", "echo", MAPPER.valueToTree("x"))), StopReason.TOOL_CALL);
        var llmClient = new ScriptedLlmClient(assistant1);
        var eventSink = new RecordingEventSink();
        var config = config(llmClient, eventSink);
        var context = contextWithTools(cancelTool, echo);
        var loop = new AgentLoop(executor);

        var result = loop.runPrompt(List.of(user("hi")), context, config, source.token());

        assertEquals(1, llmClient.receivedRequests().size()); // aborted, no model 2
        var messages = result.context().messages();
        assertEquals(4, messages.size()); // [user, assistant1, cancelResult, aborted]
        var aborted = (AgentMessage.Assistant) messages.get(3);
        assertEquals(StopReason.ABORTED, aborted.stopReason());
        // echo did NOT run: only the cancel tool produced a result.
        var trs = toolResultsIn(result);
        assertEquals(1, trs.size());
        assertEquals("cancel", trs.get(0).toolName());
        // Only ToolStarted for the cancel tool, not for echo (boundary 4 skipped it).
        var starts = eventSink.events().stream()
                .filter(e -> e instanceof AgentEvent.ToolStarted)
                .toList();
        assertEquals(1, starts.size());
    }

    // --- case T1: all-terminating results stop without a second model call ---

    @Test
    void allTerminatingResultsStopWithoutSecondModelCall() {
        var terminating = TestTools.terminating();
        var assistant1 = assistant(
                List.of(toolCall("c1", "terminating", MAPPER.createObjectNode())), StopReason.TOOL_CALL);
        var llmClient = new ScriptedLlmClient(assistant1);
        var eventSink = new RecordingEventSink();
        var config = config(llmClient, eventSink);
        var context = contextWithTools(terminating);
        var loop = new AgentLoop(executor);

        var result = loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token());

        assertEquals(1, llmClient.receivedRequests().size());
        var trs = toolResultsIn(result);
        assertEquals(1, trs.size());
        assertTrue(trs.get(0).terminate());
    }

    // --- case T2: mixed terminating results continue to a second model call ---

    @Test
    void mixedTerminatingResultsContinueToSecondModelCall() {
        var terminating = TestTools.terminating();
        var echo = TestTools.echo();
        var assistant1 = assistant(List.of(
                toolCall("c1", "terminating", MAPPER.createObjectNode()),
                toolCall("c2", "echo", MAPPER.valueToTree("hi"))), StopReason.TOOL_CALL);
        var assistant2 = assistant(List.of(new Content.Text("done")), StopReason.STOP);
        var llmClient = new ScriptedLlmClient(assistant1, assistant2);
        var eventSink = new RecordingEventSink();
        var config = config(llmClient, eventSink);
        var context = contextWithTools(terminating, echo);
        var loop = new AgentLoop(executor);

        var result = loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token());

        assertEquals(2, llmClient.receivedRequests().size());
        var trs = toolResultsIn(result);
        assertEquals(2, trs.size());
        assertEquals("terminating", trs.get(0).toolName());
        assertTrue(trs.get(0).terminate());
        assertEquals("echo", trs.get(1).toolName());
        assertFalse(trs.get(1).terminate());
    }
}
