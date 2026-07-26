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

    private static List<AgentMessage.ToolResult> toolResultsIn(LoopResult result) {
        return result.context().messages().stream()
                .filter(m -> m instanceof AgentMessage.ToolResult)
                .map(m -> (AgentMessage.ToolResult) m)
                .toList();
    }

    // --- case 1 ---

    @Test
    void singleModelAnswerEndsLoop() {
        // Given: a scripted STOP assistant and a recording sink.
        var assistant = assistant(List.of(new Content.Text("hello")), StopReason.STOP);
        var llmClient = new ScriptedLlmClient(assistant);
        var eventSink = new RecordingEventSink();
        var config = config(llmClient, eventSink);
        var context = new AgentContext("sys", List.of(), List.of());
        var prompt = user("hi");
        var loop = new AgentLoop(executor);

        // When
        LoopResult result = loop.runPrompt(List.of(prompt), context, config, new CancellationSource().token());

        // Then: model called once; context = [user, assistant]
        assertEquals(1, llmClient.receivedRequests().size());
        var messages = result.context().messages();
        assertEquals(2, messages.size());
        assertSame(prompt, messages.get(0));
        assertSame(assistant, messages.get(1));
        assertEquals(2, result.newMessages().size());

        // And: event lifecycle = AgentStarted, TurnStarted, MessageCompleted(user),
        //      MessageCompleted(assistant), TurnCompleted, AgentCompleted
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

    // --- case 2 ---

    @Test
    void singleToolCallExecutesAndReCallsModel() {
        // Given: model returns a tool call, then STOP.
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

        // When
        var result = loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token());

        // Then: model called twice; context = [user, assistant1, toolResult, assistant2]
        assertEquals(2, llmClient.receivedRequests().size());
        var messages = result.context().messages();
        assertEquals(4, messages.size());
        var tr = (AgentMessage.ToolResult) messages.get(2);
        assertEquals("echo", tr.toolName());
        assertFalse(tr.error());
        assertEquals("echo this", ((Content.Text) tr.content().get(0)).text());
        // And: ToolStarted + ToolCompleted emitted
        assertTrue(eventSink.events().stream().anyMatch(e -> e instanceof AgentEvent.ToolStarted));
        assertTrue(eventSink.events().stream().anyMatch(e -> e instanceof AgentEvent.ToolCompleted));
    }

    // --- case 3 ---

    @Test
    void parallelToolsStartConcurrentlyAndWriteBackInSourceOrder() throws Exception {
        // Given: two parallel blocking tools sharing latches.
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

        // When: run on a virtual thread so the test can observe + release.
        var future = CompletableFuture.supplyAsync(
                () -> loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token()),
                executor);
        // Then: both tools started concurrently (latch reaches 0).
        assertTrue(started.await(2, TimeUnit.SECONDS));
        release.countDown();
        var result = future.get(5, TimeUnit.SECONDS);

        // And: model called twice; tool results written back in source order [b1, b2].
        assertEquals(2, llmClient.receivedRequests().size());
        var trs = toolResultsIn(result);
        assertEquals(2, trs.size());
        assertEquals("b1", trs.get(0).toolName());
        assertEquals("b2", trs.get(1).toolName());
    }

    // --- case 4 ---

    @Test
    void sequentialBatchRunsToolsOneAtATime() throws Exception {
        // Given: b1 declares SEQUENTIAL, so the whole batch runs one at a time.
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

        // When
        var future = CompletableFuture.supplyAsync(
                () -> loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token()),
                executor);
        // Then: b1 starts first; b2 has NOT started (sequential).
        assertTrue(started1.await(2, TimeUnit.SECONDS));
        assertEquals(1, started2.getCount());
        release1.countDown();
        assertTrue(started2.await(2, TimeUnit.SECONDS));
        release2.countDown();
        var result = future.get(5, TimeUnit.SECONDS);

        // And: tool results in source order [b1, b2].
        var trs = toolResultsIn(result);
        assertEquals(2, trs.size());
        assertEquals("b1", trs.get(0).toolName());
        assertEquals("b2", trs.get(1).toolName());
    }

    // --- case 5 ---

    @Test
    void toolErrorsProduceErrorResultsAndLoopContinues() {
        // Given: three tool calls — not-found, arg-invalid, execute-fail.
        var echo = TestTools.echo();
        var failing = TestTools.failing();
        var assistant1 = assistant(List.of(
                toolCall("c1", "unknown", MAPPER.createObjectNode()),
                toolCall("c2", "echo", MAPPER.valueToTree(Map.of("x", 1))),
                toolCall("c3", "failing", MAPPER.createObjectNode())), StopReason.TOOL_CALL);
        var assistant2 = assistant(List.of(new Content.Text("recovered")), StopReason.STOP);
        var llmClient = new ScriptedLlmClient(assistant1, assistant2);
        var config = config(llmClient, new RecordingEventSink());
        var context = contextWithTools(echo, failing);  // "unknown" not registered
        var loop = new AgentLoop(executor);

        // When
        var result = loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token());

        // Then: model called twice (error results let the model recover).
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

    // --- case 6 ---

    @Test
    void lengthStopReasonFailsToolCallsWithoutExecution() {
        // Given: model returns LENGTH with a tool call.
        var echo = TestTools.echo();
        var assistant1 = assistant(
                List.of(toolCall("c1", "echo", MAPPER.valueToTree("hello"))),
                StopReason.LENGTH);
        var assistant2 = assistant(List.of(new Content.Text("recovered")), StopReason.STOP);
        var llmClient = new ScriptedLlmClient(assistant1, assistant2);
        var config = config(llmClient, new RecordingEventSink());
        var context = contextWithTools(echo);
        var loop = new AgentLoop(executor);

        // When
        var result = loop.runPrompt(List.of(user("hi")), context, config, new CancellationSource().token());

        // Then: model called twice (truncated tool call is failed, loop continues).
        assertEquals(2, llmClient.receivedRequests().size());
        var trs = toolResultsIn(result);
        assertEquals(1, trs.size());
        assertTrue(trs.get(0).error());
        // And: echo was NOT executed — result content is the truncation message, not "hello".
        var text = (Content.Text) trs.get(0).content().get(0);
        assertTrue(text.text().contains("truncated"));
    }
}
