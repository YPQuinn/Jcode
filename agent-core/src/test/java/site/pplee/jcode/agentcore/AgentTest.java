package site.pplee.jcode.agentcore;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.agentcore.support.RecordingEventSink;
import site.pplee.jcode.agentcore.support.TestTools;

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

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AgentTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef MODEL = new ModelRef("test", "test-api", "test-model");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Message.User user(String text) {
        return new Message.User(List.of(new Content.Text(text)), T1);
    }

    private static Message.Assistant assistantText(String text, StopReason reason) {
        return Message.Assistant.of(List.of(new Content.Text(text)), reason, T1);
    }

    private AgentConfig configWith(ModelClient client, AgentEventSink sink) {
        return new AgentConfig(
                new AgentContext("sys", List.of(), List.of()),
                MODEL, client, MAPPER, null, null, null, sink, null, null);
    }

    // --- basic run ---

    @Test
    void promptReturnsResultWithAssistantMessage() throws Exception {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("hello", StopReason.STOP));
        try (var agent = new Agent(configWith(client, AgentEventSink.noop()))) {
            var result = agent.prompt(user("hi")).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(2, result.context().messages().size());
            assertFalse(agent.isRunning());
        }
    }

    @Test
    void contextAccessorReflectsLatestRun() throws Exception {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("r1", StopReason.STOP),
                assistantText("r2", StopReason.STOP));
        try (var agent = new Agent(configWith(client, AgentEventSink.noop()))) {
            agent.prompt(user("a")).toCompletableFuture().get(2, TimeUnit.SECONDS);
            // context updated after run
            assertEquals(2, agent.context().messages().size());

            // a new prompt appends; context reflects the latest run
            agent.prompt(user("b")).toCompletableFuture().get(2, TimeUnit.SECONDS);
            // a, assistant(r1), b, assistant(r2)
            assertEquals(4, agent.context().messages().size());
        }
    }

    // --- active-run protection ---

    @Test
    void concurrentPromptFailsFast() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blockingClient = new ModelClient() {
            @Override
            public AssistantMessageStream stream(
                    ModelRequest request, CancellationSignal cancellation) {
                started.countDown();
                var stream = new AssistantMessageStream();
                CompletableFuture.runAsync(() -> {
                    try { release.await(2, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    var msg = assistantText("slow", StopReason.STOP);
                    stream.push(new AssistantMessageEvent.Start(msg));
                    stream.push(new AssistantMessageEvent.Done(StopReason.STOP, msg));
                });
                return stream;
            }
        };
        try (var agent = new Agent(configWith(blockingClient, AgentEventSink.noop()))) {
            var first = agent.prompt(user("a"));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            // second concurrent prompt must fail exceptionally, no sync throw
            var second = agent.prompt(user("b")).toCompletableFuture();
            assertTrue(second.isCompletedExceptionally());
            second.handle((r, e) -> {
                assertInstanceOf(IllegalStateException.class, e);
                return null;
            }).toCompletableFuture().get();
            release.countDown();
            first.toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void continueRunOnEmptyContextFailsFast() {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("x", StopReason.STOP));
        try (var agent = new Agent(configWith(client, AgentEventSink.noop()))) {
            var fut = agent.continueRun().toCompletableFuture();
            assertTrue(fut.isCompletedExceptionally());
            fut.handle((r, e) -> {
                assertInstanceOf(IllegalStateException.class, e);
                return null;
            }).toCompletableFuture().join();
        }
    }

    @Test
    void continueRunAfterAssistantFailsFast() throws Exception {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("a", StopReason.STOP));
        try (var agent = new Agent(configWith(client, AgentEventSink.noop()))) {
            agent.prompt(user("hi")).toCompletableFuture().get(2, TimeUnit.SECONDS);
            // last message is assistant -> continueRun must fail
            var fut = agent.continueRun().toCompletableFuture();
            assertTrue(fut.isCompletedExceptionally());
            fut.handle((r, e) -> {
                assertInstanceOf(IllegalStateException.class, e);
                return null;
            }).toCompletableFuture().join();
        }
    }

    // --- close & abort ---

    @Test
    void closeAbortsActiveRunAndReleasesExecutor() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blockingClient = new ModelClient() {
            @Override
            public AssistantMessageStream stream(
                    ModelRequest request, CancellationSignal cancellation) {
                started.countDown();
                var stream = new AssistantMessageStream();
                CompletableFuture.runAsync(() -> {
                    try { release.await(2, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    var msg = assistantText("slow", StopReason.STOP);
                    stream.push(new AssistantMessageEvent.Start(msg));
                    stream.push(new AssistantMessageEvent.Done(StopReason.STOP, msg));
                });
                return stream;
            }
        };
        var agent = new Agent(configWith(blockingClient, AgentEventSink.noop()));
        var fut = agent.prompt(user("a")).toCompletableFuture();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        agent.close();
        // the future should settle (either cancelled-completed or exceptionally)
        try {
            fut.get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        // subsequent prompt must fail: agent closed
        var after = agent.prompt(user("b")).toCompletableFuture();
        assertTrue(after.isCompletedExceptionally());
    }

    @Test
    void abortRequestsCancellationOfActiveRun() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var seenCancelled = new AtomicBoolean(false);
        var blockingClient = new ModelClient() {
            @Override
            public AssistantMessageStream stream(
                    ModelRequest request, CancellationSignal cancellation) {
                started.countDown();
                var stream = new AssistantMessageStream();
                CompletableFuture.runAsync(() -> {
                    try { release.await(2, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    seenCancelled.set(cancellation.isCancelled());
                    var msg = assistantText("slow", StopReason.STOP);
                    stream.push(new AssistantMessageEvent.Start(msg));
                    stream.push(new AssistantMessageEvent.Done(StopReason.STOP, msg));
                });
                return stream;
            }
        };
        try (var agent = new Agent(configWith(blockingClient, AgentEventSink.noop()))) {
            var fut = agent.prompt(user("a"));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            agent.abort();
            release.countDown();
            fut.toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertTrue(seenCancelled.get(), "abort should propagate to model signal");
        }
    }

    @Test
    void abortWithNoActiveRunIsNoop() {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("x", StopReason.STOP));
        try (var agent = new Agent(configWith(client, AgentEventSink.noop()))) {
            agent.abort(); // no throw, no active run
            assertFalse(agent.isRunning());
        }
    }

    // --- steering & follow-up via Agent queues ---

    @Test
    void steerInjectsBeforeNextModelCall() throws Exception {
        // To guarantee the steering message lands before the 2nd model call,
        // enqueue it from inside the tool's execute() — the steering drain
        // happens at the top of the next loop iteration, which is strictly
        // after the tool returns.
        var ref = new java.util.concurrent.atomic.AtomicReference<Agent>();
        var steeringTool = new site.pplee.jcode.agentcore.tool.AgentTool<Object>() {
            @Override public String name() { return "echo"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override public java.util.concurrent.CompletionStage<site.pplee.jcode.agentcore.tool.ToolExecutionResult> execute(
                    String id, Object args, site.pplee.jcode.agentcore.tool.ToolUpdateSink updates, site.pplee.jcode.ai.concurrent.CancellationSignal c) {
                ref.get().steer(user("steer"));
                return java.util.concurrent.CompletableFuture.completedFuture(
                        site.pplee.jcode.agentcore.tool.ToolExecutionResult.success(
                                List.of(new Content.Text("ok"))));
            }
        };
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                Message.Assistant.of(List.of(
                        new Content.ToolCall("c1", "echo", MAPPER.getNodeFactory().textNode("hi"))),
                        StopReason.TOOL_CALL, T1),
                assistantText("final", StopReason.STOP));
        var cfg = new AgentConfig(
                new AgentContext("sys", List.of(), List.of(steeringTool)),
                MODEL, client, MAPPER, null, null, null, null, QueueMode.ALL, null);
        try (var agent = new Agent(cfg)) {
            ref.set(agent);
            var result = agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);

            // 2nd request projected: prompt + assistant(toolcall) + toolresult + steer(user)
            var secondReq = client.receivedRequests().get(1);
            assertEquals(4, secondReq.messages().size());
            var fourth = secondReq.messages().get(3);
            assertInstanceOf(Message.User.class, fourth);
            assertEquals("steer", ((Content.Text) ((Message.User) fourth).content().get(0)).text());
        }
    }

    @Test
    void followUpKeepsRunGoing() throws Exception {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient(
                assistantText("first", StopReason.STOP),
                assistantText("second", StopReason.STOP));
        try (var agent = new Agent(configWith(client, AgentEventSink.noop()))) {
            agent.followUpMode(QueueMode.ALL);
            agent.followUp(user("again"));
            var result = agent.prompt(user("hi")).toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(4, result.context().messages().size());
        }
    }

    // --- construction ---

    @Test
    void constructorRejectsNullConfig() {
        assertThrows(NullPointerException.class, () -> new Agent(null));
    }

    @Test
    void defaultsAreApplied() {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient();
        var cfg = new AgentConfig(
                new AgentContext("sys", List.of(), List.of()),
                MODEL, client, MAPPER, null, null, null, null, null, null);
        try (var agent = new Agent(cfg)) {
            assertEquals(QueueMode.ONE_AT_A_TIME, agent.steeringMode());
            assertEquals(QueueMode.ONE_AT_A_TIME, agent.followUpMode());
        }
    }

    @Test
    void queueModeAccessorsAndSetters() {
        var client = new site.pplee.jcode.agentcore.support.ScriptedModelClient();
        try (var agent = new Agent(configWith(client, AgentEventSink.noop()))) {
            agent.steeringMode(QueueMode.ALL);
            assertEquals(QueueMode.ALL, agent.steeringMode());
            agent.followUpMode(QueueMode.ALL);
            assertEquals(QueueMode.ALL, agent.followUpMode());
            assertThrows(NullPointerException.class, () -> agent.steeringMode(null));
            assertThrows(NullPointerException.class, () -> agent.followUpMode(null));
        }
    }
}
