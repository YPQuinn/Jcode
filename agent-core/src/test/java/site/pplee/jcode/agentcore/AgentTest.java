package site.pplee.jcode.agentcore;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.model.AgentContext;
import site.pplee.jcode.agentcore.model.AgentMessage;
import site.pplee.jcode.agentcore.model.Content;
import site.pplee.jcode.agentcore.model.ModelRef;
import site.pplee.jcode.agentcore.model.StopReason;
import site.pplee.jcode.agentcore.model.ToolExecutionMode;
import site.pplee.jcode.agentcore.spi.AgentTool;
import site.pplee.jcode.agentcore.spi.LlmClient;
import site.pplee.jcode.agentcore.support.ScriptedLlmClient;
import site.pplee.jcode.agentcore.support.TestTools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef MODEL = new ModelRef("test", "test-model");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- helpers ---

    private static AgentMessage.User user(String text) {
        return new AgentMessage.User(List.of(new Content.Text(text)), T1);
    }

    private static AgentMessage.Assistant assistant(List<Content> content, StopReason reason) {
        return new AgentMessage.Assistant(content, reason, null, T1);
    }

    private static AgentMessage.Assistant stopAssistant(String text) {
        return assistant(List.of(new Content.Text(text)), StopReason.STOP);
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

    private static AgentConfig config(AgentContext context, LlmClient llmClient) {
        return new AgentConfig(context, MODEL, llmClient, MAPPER, null, null, null, null, null);
    }

    // --- case 1: prompt() updates agent context ---

    @Test
    void promptUpdatesAgentContext() throws Exception {
        var llmClient = new ScriptedLlmClient(stopAssistant("done"));
        var config = config(new AgentContext("sys", List.of(), List.of()), llmClient);
        try (var agent = new Agent(config)) {
            var result = agent.prompt(user("hi"))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertSame(result.context(), agent.context());
            var messages = agent.context().messages();
            assertEquals(2, messages.size());
            assertInstanceOf(AgentMessage.User.class, messages.get(0));
            assertInstanceOf(AgentMessage.Assistant.class, messages.get(1));
            assertFalse(agent.isRunning());
        }
    }

    // --- case 2: second prompt/continueRun during active run fails ---

    @Test
    void secondCallDuringActiveRunFails() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocker = TestTools.blocking("blocker", started, release, ToolExecutionMode.PARALLEL);
        var assistant1 = assistant(
                List.of(toolCall("c1", "blocker", MAPPER.createObjectNode())),
                StopReason.TOOL_CALL);
        var assistant2 = stopAssistant("done");
        var llmClient = new ScriptedLlmClient(assistant1, assistant2);
        var config = config(contextWithTools(blocker), llmClient);
        try (var agent = new Agent(config)) {
            var future1 = agent.prompt(user("hi"));
            assertTrue(started.await(2, TimeUnit.SECONDS));

            var secondPrompt = agent.prompt(user("again"));
            var secondContinue = agent.continueRun();

            var ex1 = assertThrows(ExecutionException.class,
                    () -> secondPrompt.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(ex1.getCause() instanceof IllegalStateException);
            assertTrue(ex1.getCause().getMessage().contains("already running"));
            var ex2 = assertThrows(ExecutionException.class,
                    () -> secondContinue.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(ex2.getCause() instanceof IllegalStateException);
            assertTrue(ex2.getCause().getMessage().contains("already running"));

            release.countDown();
            future1.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertFalse(agent.isRunning());
        }
    }

    // --- case 3: followUp during active run is consumed ---

    @Test
    void followUpDuringActiveRunIsConsumed() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocker = TestTools.blocking("blocker", started, release, ToolExecutionMode.PARALLEL);
        var assistant1 = assistant(
                List.of(toolCall("c1", "blocker", MAPPER.createObjectNode())),
                StopReason.TOOL_CALL);
        var assistant2 = stopAssistant("after tool");
        var assistant3 = stopAssistant("after followup");
        var llmClient = new ScriptedLlmClient(assistant1, assistant2, assistant3);
        var config = config(contextWithTools(blocker), llmClient);
        try (var agent = new Agent(config)) {
            var future = agent.prompt(user("hi"));
            assertTrue(started.await(2, TimeUnit.SECONDS));

            var followUpMsg = user("also do this");
            agent.followUp(followUpMsg);
            release.countDown();

            var result = future.toCompletableFuture().get(5, TimeUnit.SECONDS);
            var messages = result.context().messages();
            // [user, asst1(TOOL_CALL), toolResult, asst2(STOP), followUpMsg, asst3(STOP)]
            assertEquals(6, messages.size());
            assertSame(followUpMsg, messages.get(4));
            assertEquals(3, llmClient.receivedRequests().size());
            assertTrue(llmClient.receivedRequests().get(2).messages().contains(followUpMsg));
            assertSame(result.context(), agent.context());
            assertFalse(agent.isRunning());
        }
    }

    // --- case 4: abort propagates to tool token and produces ABORTED ---

    @Test
    void abortPropagatesToToolTokenAndProducesAborted() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelled = new AtomicBoolean(false);
        var recorder = TestTools.recordingBlocking("record", started, release, cancelled);
        var assistant1 = assistant(
                List.of(toolCall("c1", "record", MAPPER.createObjectNode())),
                StopReason.TOOL_CALL);
        var llmClient = new ScriptedLlmClient(assistant1);
        var config = config(contextWithTools(recorder), llmClient);
        try (var agent = new Agent(config)) {
            var future = agent.prompt(user("hi"));
            assertTrue(started.await(2, TimeUnit.SECONDS));

            agent.abort();
            release.countDown();

            var result = future.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(cancelled.get());
            var messages = result.context().messages();
            var last = (AgentMessage.Assistant) messages.get(messages.size() - 1);
            assertEquals(StopReason.ABORTED, last.stopReason());
            assertFalse(agent.isRunning());
        }
    }

    // --- case 5: continueRun fails when last message is assistant ---

    @Test
    void continueRunFailsWhenLastMessageIsAssistant() {
        var initial = new AgentContext("sys", List.of(stopAssistant("already done")), List.of());
        var config = config(initial, new ScriptedLlmClient());
        try (var agent = new Agent(config)) {
            var future = agent.continueRun();
            var ex = assertThrows(ExecutionException.class,
                    () -> future.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof IllegalStateException);
            assertTrue(ex.getCause().getMessage().contains("assistant"));
            assertSame(initial, agent.context());
            assertFalse(agent.isRunning());
        }
    }

    // --- bonus: continueRun fails when context is empty ---

    @Test
    void continueRunFailsWhenContextIsEmpty() {
        var initial = new AgentContext("sys", List.of(), List.of());
        var config = config(initial, new ScriptedLlmClient());
        try (var agent = new Agent(config)) {
            var future = agent.continueRun();
            var ex = assertThrows(ExecutionException.class,
                    () -> future.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof IllegalStateException);
            assertTrue(ex.getCause().getMessage().contains("no messages"));
            assertFalse(agent.isRunning());
        }
    }

    // --- F3: full drive through public API: tool call -> tool result -> STOP -> follow-up -> STOP ---

    @Test
    void fullDriveToolCallFollowUpFinalAnswer() throws Exception {
        var echo = TestTools.echo();
        var assistant1 = assistant(
                List.of(toolCall("c1", "echo", MAPPER.valueToTree("hello"))),
                StopReason.TOOL_CALL);
        var assistant2 = stopAssistant("final answer");
        var assistant3 = stopAssistant("after followup");
        var llmClient = new ScriptedLlmClient(assistant1, assistant2, assistant3);
        var config = config(contextWithTools(echo), llmClient);
        try (var agent = new Agent(config)) {
            // pre-enqueue follow-up; the loop drains it at step 13 after the
            // first STOP, then calls the model once more.
            var followUpMsg = user("now summarize");
            agent.followUp(followUpMsg);

            var result = agent.prompt(user("hi"))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            var messages = result.context().messages();
            // [user, asst1(TOOL_CALL), toolResult, asst2(STOP), followUpMsg, asst3(STOP)]
            assertEquals(6, messages.size());
            assertInstanceOf(AgentMessage.User.class, messages.get(0));
            var asst1 = (AgentMessage.Assistant) messages.get(1);
            assertEquals(StopReason.TOOL_CALL, asst1.stopReason());
            var tr = (AgentMessage.ToolResult) messages.get(2);
            assertEquals("echo", tr.toolName());
            assertFalse(tr.error());
            assertEquals("hello", ((Content.Text) tr.content().get(0)).text());
            var asst2 = (AgentMessage.Assistant) messages.get(3);
            assertEquals(StopReason.STOP, asst2.stopReason());
            assertSame(followUpMsg, messages.get(4));
            var asst3 = (AgentMessage.Assistant) messages.get(5);
            assertEquals(StopReason.STOP, asst3.stopReason());

            // 3 model calls; each request's messages reflect the call order
            assertEquals(3, llmClient.receivedRequests().size());
            assertEquals(1, llmClient.receivedRequests().get(0).messages().size());
            assertEquals(3, llmClient.receivedRequests().get(1).messages().size());
            assertTrue(llmClient.receivedRequests().get(2).messages().contains(followUpMsg));

            assertSame(result.context(), agent.context());
            assertFalse(agent.isRunning());
        }
    }
}
