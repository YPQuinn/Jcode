package site.pplee.jcode.agentcore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.event.AgentEventSink;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.support.RecordingEventSink;
import site.pplee.jcode.agentcore.support.ScriptedModelClient;
import site.pplee.jcode.agentcore.tool.AfterToolCall;
import site.pplee.jcode.agentcore.tool.BeforeToolCall;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;

import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.ModelRef;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPipelineTest {
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

    private record TestArgs(String text) {}

    private static final JsonNode TEXT_SCHEMA = parseSchema(
            "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}");

    private static JsonNode parseSchema(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Message.User user(String text) {
        return new Message.User(List.of(new Content.Text(text)), T1);
    }

    private static StandardAgentMessage userMsg(String text) {
        return StandardAgentMessage.of(user(text));
    }

    private static Message.Assistant assistantText(String text, StopReason reason) {
        return Message.Assistant.of(List.of(new Content.Text(text)), reason, T1);
    }

    private static Content.ToolCall toolCall(String id, String name, JsonNode args) {
        return new Content.ToolCall(id, name, args);
    }

    private static AgentContext contextWithTools(AgentTool<?>... tools) {
        return new AgentContext("sys", List.of(), List.of(tools));
    }

    private AgentLoopConfig config(ModelClient client, AgentEventSink sink) {
        return new AgentLoopConfig(MODEL, client, MAPPER, null, null, null, null, null, sink);
    }

    private AgentLoopConfig configWithHooks(
            ModelClient client, AgentEventSink sink,
            BeforeToolCall before, AfterToolCall after) {
        return new AgentLoopConfig(MODEL, client, MAPPER, null, before, after, null, null, sink);
    }

    private LoopResult runPrompt(ModelClient client, AgentContext ctx, AgentEventSink sink) {
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        return loop.runPrompt(List.of(userMsg("hi")), ctx, config(client, sink), source.signal());
    }

    private LoopResult runPromptWithHooks(
            ModelClient client, AgentContext ctx, AgentEventSink sink,
            BeforeToolCall before, AfterToolCall after) {
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        return loop.runPrompt(List.of(userMsg("hi")), ctx,
                configWithHooks(client, sink, before, after), source.signal());
    }

    private record RunResult(LoopResult result, RecordingEventSink sink) {}

    private RunResult run(ModelClient client, AgentContext ctx) {
        var sink = new RecordingEventSink();
        var loop = new AgentLoop(executor);
        var source = new CancellationSource();
        var result = loop.runPrompt(List.of(userMsg("hi")), ctx,
                config(client, sink), source.signal());
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

    // --- 1. Schema failure does not execute the tool ---

    @Test
    void schemaFailureDoesNotExecuteTool() {
        var execCount = new AtomicInteger();
        var tool = new AgentTool<TestArgs>() {
            @Override public String name() { return "schema_tool"; }
            @Override public Class<TestArgs> argumentType() { return TestArgs.class; }
            @Override public JsonNode parametersSchema() { return TEXT_SCHEMA; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, TestArgs args, ToolUpdateSink updates, CancellationSignal c) {
                execCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text(args.text()))));
            }
        };
        var ctx = contextWithTools(tool);
        // call with empty object - missing required field "text"
        var args = MAPPER.createObjectNode();
        var client = new ScriptedModelClient(
                Message.Assistant.of(List.of(toolCall("c1", "schema_tool", args)), StopReason.TOOL_CALL, T1),
                assistantText("done", StopReason.STOP));
        var run = run(client, ctx);

        assertEquals(0, execCount.get(), "tool must not be executed when schema fails");

        var toolCompleted = run.sink().events().stream()
                .filter(e -> e instanceof AgentEvent.ToolCompleted)
                .map(e -> ((AgentEvent.ToolCompleted) e).result())
                .findFirst().orElseThrow();
        assertTrue(toolCompleted.error(), "result must be an error");
        var text = (Content.Text) toolCompleted.content().get(0);
        assertTrue(text.text().contains("schema validation failed"),
                "error must mention schema validation: " + text.text());
    }

    // --- 2. Before hook can block execution ---

    @Test
    void beforeHookCanBlockExecution() {
        var execCount = new AtomicInteger();
        var tool = new AgentTool<Object>() {
            @Override public String name() { return "gated"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                execCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("ok"))));
            }
        };
        var ctx = contextWithTools(tool);
        var client = new ScriptedModelClient(
                Message.Assistant.of(List.of(
                        toolCall("c1", "gated", MAPPER.getNodeFactory().textNode("hi"))),
                        StopReason.TOOL_CALL, T1),
                assistantText("done", StopReason.STOP));
        BeforeToolCall blocker = (call, t, preparedArgs, context, c) ->
                CompletableFuture.completedStage(new BeforeToolCall.Decision.Block("not allowed"));
        var run = runWithHooks(client, ctx, blocker, AfterToolCall.noop());

        assertEquals(0, execCount.get(), "tool must not be executed when before hook blocks");

        var toolCompleted = run.sink().events().stream()
                .filter(e -> e instanceof AgentEvent.ToolCompleted)
                .map(e -> ((AgentEvent.ToolCompleted) e).result())
                .findFirst().orElseThrow();
        assertTrue(toolCompleted.error());
        var text = (Content.Text) toolCompleted.content().get(0);
        assertTrue(text.text().contains("blocked by before hook"),
                "error must mention before hook: " + text.text());
        assertTrue(text.text().contains("not allowed"),
                "error must contain block reason: " + text.text());
    }

    // --- 3. After hook can patch content, error, and terminate ---

    @Test
    void afterHookPatchesContent() {
        var tool = new AgentTool<Object>() {
            @Override public String name() { return "patch_me"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("original"))));
            }
        };
        var ctx = contextWithTools(tool);
        var client = new ScriptedModelClient(
                Message.Assistant.of(List.of(
                        toolCall("c1", "patch_me", MAPPER.getNodeFactory().textNode("hi"))),
                        StopReason.TOOL_CALL, T1),
                assistantText("done", StopReason.STOP));
        AfterToolCall patcher = (call, t, result, context, c) ->
                CompletableFuture.completedStage(
                        new ToolExecutionResult(
                                List.of(new Content.Text("patched")),
                                false,
                                false));
        var run = runWithHooks(client, ctx, BeforeToolCall.noop(), patcher);

        var toolCompleted = run.sink().events().stream()
                .filter(e -> e instanceof AgentEvent.ToolCompleted)
                .map(e -> ((AgentEvent.ToolCompleted) e).result())
                .findFirst().orElseThrow();
        assertFalse(toolCompleted.error());
        var text = (Content.Text) toolCompleted.content().get(0);
        assertEquals("patched", text.text(), "after hook must replace content");
    }

    @Test
    void afterHookCanAddTerminate() {
        var tool = new AgentTool<Object>() {
            @Override public String name() { return "term_me"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("ok"))));
            }
        };
        var ctx = contextWithTools(tool);
        var client = new ScriptedModelClient(
                Message.Assistant.of(List.of(
                        toolCall("c1", "term_me", MAPPER.getNodeFactory().textNode("hi"))),
                        StopReason.TOOL_CALL, T1),
                assistantText("never", StopReason.STOP));
        AfterToolCall terminator = (call, t, result, context, c) ->
                CompletableFuture.completedStage(
                        ToolExecutionResult.success(List.of(new Content.Text("ok")), true));
        var run = runWithHooks(client, ctx, BeforeToolCall.noop(), terminator);

        assertEquals(1, client.receivedRequests().size(),
                "run must stop after terminate — no 2nd model call");
    }

    // --- 4. Tool increments arrive in real time ---

    @Test
    void toolIncrementsArriveInRealTime() {
        var tool = new AgentTool<Object>() {
            @Override public String name() { return "streaming"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                updates.update(new Content.Text("partial-1")).toCompletableFuture().join();
                updates.update(new Content.Text("partial-2")).toCompletableFuture().join();
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("final"))));
            }
        };
        var ctx = contextWithTools(tool);
        var client = new ScriptedModelClient(
                Message.Assistant.of(List.of(
                        toolCall("c1", "streaming", MAPPER.getNodeFactory().textNode("hi"))),
                        StopReason.TOOL_CALL, T1),
                assistantText("done", StopReason.STOP));
        var run = run(client, ctx);

        var events = run.sink().events();
        var toolStartedIdx = indexOf(events, e -> e instanceof AgentEvent.ToolStarted);
        var firstUpdateIdx = indexOf(events, e -> e instanceof AgentEvent.ToolUpdate);
        var toolCompletedIdx = indexOf(events, e -> e instanceof AgentEvent.ToolCompleted);

        assertTrue(toolStartedIdx < firstUpdateIdx,
                "ToolUpdate must arrive after ToolStarted");
        assertTrue(firstUpdateIdx < toolCompletedIdx,
                "ToolUpdate must arrive before ToolCompleted");

        var updates = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolUpdate)
                .map(e -> ((AgentEvent.ToolUpdate) e).update())
                .toList();
        assertEquals(2, updates.size());
        assertEquals("partial-1", ((Content.Text) updates.get(0)).text());
        assertEquals("partial-2", ((Content.Text) updates.get(1)).text());
    }

    // --- 5. After settle, increments are ignored ---

    @Test
    void settleIgnoresLateUpdates() {
        var capturedSink = new AtomicReference<ToolUpdateSink>();
        var tool = new AgentTool<Object>() {
            @Override public String name() { return "late"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, Object args, ToolUpdateSink updates, CancellationSignal c) {
                capturedSink.set(updates);
                updates.update(new Content.Text("real-time")).toCompletableFuture().join();
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text("done"))));
            }
        };
        var ctx = contextWithTools(tool);
        var client = new ScriptedModelClient(
                Message.Assistant.of(List.of(
                        toolCall("c1", "late", MAPPER.getNodeFactory().textNode("hi"))),
                        StopReason.TOOL_CALL, T1),
                assistantText("done", StopReason.STOP));
        var run = run(client, ctx);

        var updatesBefore = run.sink().events().stream()
                .filter(e -> e instanceof AgentEvent.ToolUpdate)
                .count();
        assertEquals(1, updatesBefore, "one real-time update during execution");

        // sink is now settled — late updates must be silently dropped
        capturedSink.get().update(new Content.Text("late")).toCompletableFuture().join();

        var updatesAfter = run.sink().events().stream()
                .filter(e -> e instanceof AgentEvent.ToolUpdate)
                .count();
        assertEquals(1, updatesAfter, "late update after settle must be ignored");
    }

    // --- 6. prepareArguments is called before schema validation ---

    @Test
    void prepareArgumentsInjectsDefaultsBeforeSchemaValidation() {
        var execCount = new AtomicInteger();
        var tool = new AgentTool<TestArgs>() {
            @Override public String name() { return "prep"; }
            @Override public Class<TestArgs> argumentType() { return TestArgs.class; }
            @Override public JsonNode parametersSchema() { return TEXT_SCHEMA; }
            @Override
            public JsonNode prepareArguments(JsonNode arguments) {
                if (arguments.isObject() && !arguments.has("text")) {
                    var patched = arguments.deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) patched)
                            .put("text", "default");
                    return patched;
                }
                return arguments;
            }
            @Override
            public CompletionStage<ToolExecutionResult> execute(
                    String id, TestArgs args, ToolUpdateSink updates, CancellationSignal c) {
                execCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                        ToolExecutionResult.success(List.of(new Content.Text(args.text()))));
            }
        };
        var ctx = contextWithTools(tool);
        // empty object — prepareArguments injects "text":"default", schema validation passes
        var args = MAPPER.createObjectNode();
        var client = new ScriptedModelClient(
                Message.Assistant.of(List.of(toolCall("c1", "prep", args)), StopReason.TOOL_CALL, T1),
                assistantText("done", StopReason.STOP));
        var run = run(client, ctx);

        assertEquals(1, execCount.get(), "tool must be executed after prepareArguments fixes args");

        var toolCompleted = run.sink().events().stream()
                .filter(e -> e instanceof AgentEvent.ToolCompleted)
                .map(e -> ((AgentEvent.ToolCompleted) e).result())
                .findFirst().orElseThrow();
        assertFalse(toolCompleted.error());
        var text = (Content.Text) toolCompleted.content().get(0);
        assertEquals("default", text.text(), "execute must see the injected default");
    }

    private static int indexOf(List<AgentEvent> events, java.util.function.Predicate<AgentEvent> test) {
        for (int i = 0; i < events.size(); i++) {
            if (test.test(events.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
