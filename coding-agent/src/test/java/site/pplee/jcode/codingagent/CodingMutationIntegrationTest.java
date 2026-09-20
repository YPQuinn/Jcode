package site.pplee.jcode.codingagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.codingagent.event.CodingAgentEvent;
import site.pplee.jcode.codingagent.event.CodingAgentEventSink;
import site.pplee.jcode.codingagent.support.NativeToolTestSupport;
import site.pplee.jcode.codingagent.support.ScriptedModelClient;
import site.pplee.jcode.codingagent.tool.BashConfig;
import site.pplee.jcode.codingagent.tool.CodingTool;
import site.pplee.jcode.codingagent.tool.CodingToolConfig;
import site.pplee.jcode.codingagent.tool.CodingToolPolicy;
import site.pplee.jcode.codingagent.tool.SearchConfig;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CodingMutationIntegrationTest {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "model");
    private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");

    @TempDir
    Path directory;

    @Test
    void explicitlyEnabledWriteAndEditRunSequentiallyAndMatchPrompt() throws Exception {
        var mapper = new ObjectMapper();
        var writeArguments = mapper.createObjectNode()
                .put("path", "source.txt")
                .put("content", "before\n");
        var replacement = mapper.createObjectNode()
                .put("oldText", "before")
                .put("newText", "after");
        var editArguments = mapper.createObjectNode().put("path", "source.txt");
        editArguments.putArray("edits").add(replacement);

        var client = new ScriptedModelClient(
                request -> {
                    assertEquals(List.of("read", "write", "edit"),
                            request.tools().stream().map(tool -> tool.name()).toList());
                    assertTrue(request.systemPrompt().contains("- write:"));
                    assertTrue(request.systemPrompt().contains("- edit:"));
                    return assistant(List.of(
                            new Content.ToolCall("write-1", "write", writeArguments),
                            new Content.ToolCall("edit-1", "edit", editArguments)),
                            StopReason.TOOL_CALL);
                },
                request -> {
                    var results = request.messages().stream()
                            .filter(Message.ToolResultMessage.class::isInstance)
                            .map(Message.ToolResultMessage.class::cast)
                            .toList();
                    assertEquals(List.of("write-1", "edit-1"),
                            results.stream().map(Message.ToolResultMessage::toolCallId).toList());
                    assertTrue(results.stream().noneMatch(Message.ToolResultMessage::error));
                    try {
                        assertEquals("after\n", Files.readString(directory.resolve("source.txt")));
                    } catch (java.io.IOException e) {
                        throw new AssertionError(e);
                    }
                    return assistant(List.of(new Content.Text("done")), StopReason.STOP);
                });
        var tools = new CodingToolConfig(
                Set.of(CodingTool.READ, CodingTool.WRITE, CodingTool.EDIT),
                null, null, null);

        try (var session = new CodingAgentSession(config(client, tools))) {
            var result = session.prompt("create and edit").toCompletableFuture().join();
            assertEquals("done", assertInstanceOf(Content.Text.class,
                    result.finalMessage().content().getFirst()).text());
        }

        assertEquals("after\n", Files.readString(directory.resolve("source.txt")));
    }

    @Test
    void explicitlyEnabledLsRunsThroughTheProductSession() throws Exception {
        Files.createDirectory(directory.resolve("src"));
        Files.writeString(directory.resolve("README.md"), "docs");
        var mapper = new ObjectMapper();
        var arguments = mapper.createObjectNode().put("path", ".").put("limit", 10);
        var client = new ScriptedModelClient(
                request -> {
                    assertEquals(List.of("ls"),
                            request.tools().stream().map(tool -> tool.name()).toList());
                    assertTrue(request.systemPrompt().contains("- ls:"));
                    return assistant(List.of(new Content.ToolCall("ls-1", "ls", arguments)),
                            StopReason.TOOL_CALL);
                },
                request -> {
                    var result = request.messages().stream()
                            .filter(Message.ToolResultMessage.class::isInstance)
                            .map(Message.ToolResultMessage.class::cast)
                            .findFirst()
                            .orElseThrow();
                    assertFalse(result.error());
                    String text = assertInstanceOf(Content.Text.class,
                            result.content().getFirst()).text();
                    assertTrue(text.startsWith("directory \"src\""));
                    assertTrue(text.contains("file \"README.md\""));
                    return assistant(List.of(new Content.Text("located")), StopReason.STOP);
                });
        var tools = new CodingToolConfig(Set.of(CodingTool.LS), null, null, null);

        try (var session = new CodingAgentSession(config(client, tools))) {
            var result = session.prompt("list files").toCompletableFuture().join();
            assertEquals("located", assertInstanceOf(Content.Text.class,
                    result.finalMessage().content().getFirst()).text());
        }
    }

    @Test
    void explicitlyEnabledSearchToolsRunThroughTheProductSession() throws Exception {
        Path ripgrep = NativeToolTestSupport.requireRipgrep();
        Files.createDirectory(directory.resolve(".git"));
        Files.writeString(directory.resolve("Source.java"), "class Source { // needle\n}\n");
        var mapper = new ObjectMapper();
        var findArguments = mapper.createObjectNode()
                .put("pattern", "*.java")
                .put("limit", 10);
        var grepArguments = mapper.createObjectNode()
                .put("pattern", "needle")
                .put("literal", true)
                .put("limit", 10);
        var client = new ScriptedModelClient(
                request -> {
                    assertEquals(List.of("grep", "find", "ls"),
                            request.tools().stream().map(tool -> tool.name()).toList());
                    assertTrue(request.systemPrompt().contains("- grep:"));
                    assertTrue(request.systemPrompt().contains("- find:"));
                    assertTrue(request.systemPrompt().contains("- ls:"));
                    return assistant(List.of(
                            new Content.ToolCall("find-1", "find", findArguments),
                            new Content.ToolCall("grep-1", "grep", grepArguments)),
                            StopReason.TOOL_CALL);
                },
                request -> {
                    var results = request.messages().stream()
                            .filter(Message.ToolResultMessage.class::isInstance)
                            .map(Message.ToolResultMessage.class::cast)
                            .toList();
                    assertEquals(List.of("find-1", "grep-1"),
                            results.stream().map(Message.ToolResultMessage::toolCallId).toList());
                    assertTrue(results.stream().noneMatch(Message.ToolResultMessage::error));
                    assertTrue(assertInstanceOf(Content.Text.class,
                            results.get(0).content().getFirst()).text().contains("Source.java"));
                    assertTrue(assertInstanceOf(Content.Text.class,
                            results.get(1).content().getFirst()).text().contains("needle"));
                    return assistant(List.of(new Content.Text("found")), StopReason.STOP);
                });
        var tools = new CodingToolConfig(
                Set.of(CodingTool.GREP, CodingTool.FIND, CodingTool.LS),
                null, new SearchConfig(ripgrep, NativeToolTestSupport.requireFd(), Map.of()), null);

        try (var session = new CodingAgentSession(config(client, tools))) {
            var result = session.prompt("find source").toCompletableFuture().join();
            assertEquals("found", assertInstanceOf(Content.Text.class,
                    result.finalMessage().content().getFirst()).text());
        }
    }

    @Test
    void codingProfileCompletesReadWriteEditAndRealBashInSourceOrder() throws Exception {
        Path bash = NativeToolTestSupport.requireBash();
        var mapper = new ObjectMapper();
        var write = mapper.createObjectNode()
                .put("path", "source.txt")
                .put("content", "before\n");
        var read = mapper.createObjectNode().put("path", "source.txt");
        var edit = mapper.createObjectNode().put("path", "source.txt");
        edit.putArray("edits").add(mapper.createObjectNode()
                .put("oldText", "before")
                .put("newText", "after"));
        var bashArguments = mapper.createObjectNode()
                .put("command", "[[ \"$(/bin/cat source.txt)\" == after ]] && printf verified")
                .put("timeout", 5);
        var client = new ScriptedModelClient(
                request -> {
                    assertEquals(List.of("read", "write", "edit", "bash"),
                            request.tools().stream().map(tool -> tool.name()).toList());
                    return assistant(List.of(
                            new Content.ToolCall("write-1", "write", write),
                            new Content.ToolCall("read-1", "read", read),
                            new Content.ToolCall("edit-1", "edit", edit),
                            new Content.ToolCall("bash-1", "bash", bashArguments)),
                            StopReason.TOOL_CALL);
                },
                request -> {
                    var results = request.messages().stream()
                            .filter(Message.ToolResultMessage.class::isInstance)
                            .map(Message.ToolResultMessage.class::cast)
                            .toList();
                    assertEquals(List.of("write-1", "read-1", "edit-1", "bash-1"),
                            results.stream().map(Message.ToolResultMessage::toolCallId).toList());
                    assertTrue(results.stream().noneMatch(Message.ToolResultMessage::error));
                    assertTrue(results.get(1).content().stream()
                            .filter(Content.Text.class::isInstance)
                            .map(Content.Text.class::cast)
                            .anyMatch(content -> content.text().contains("before")));
                    assertTrue(results.get(3).content().stream()
                            .filter(Content.Text.class::isInstance)
                            .map(Content.Text.class::cast)
                            .anyMatch(content -> content.text().contains("verified")));
                    assertEquals("after\n", readString(directory.resolve("source.txt")));
                    return assistant(List.of(new Content.Text("validated")), StopReason.STOP);
                });
        var bashConfig = new BashConfig(
                bash, Map.of(), Duration.ofSeconds(5), Duration.ofSeconds(20));

        try (var session = new CodingAgentSession(
                config(client, CodingToolConfig.coding(bashConfig)))) {
            var result = session.prompt("change and validate").toCompletableFuture().join();
            assertEquals("validated", assertInstanceOf(Content.Text.class,
                    result.finalMessage().content().getFirst()).text());
        }
    }

    @Test
    void closingSessionCancelsAndReapsItsActiveBashProcess() throws Exception {
        NativeToolTestSupport.requirePosixProcessSupport();
        Path bash = NativeToolTestSupport.requireBash();
        var mapper = new ObjectMapper();
        var client = new ScriptedModelClient(request -> assistant(List.of(new Content.ToolCall(
                        "bash-1", "bash", mapper.createObjectNode()
                        .put("command", "printf '%s' \"$$\" > pid.txt; exec /bin/sleep 30")
                        .put("timeout", 20))),
                StopReason.TOOL_CALL));
        var bashConfig = new BashConfig(
                bash, Map.of(), Duration.ofSeconds(5), Duration.ofSeconds(20));
        var session = new CodingAgentSession(config(client, CodingToolConfig.coding(bashConfig)));

        var stage = session.prompt("run").toCompletableFuture();
        long pid = awaitPid(directory.resolve("pid.txt"));
        ProcessHandle handle = ProcessHandle.of(pid).orElseThrow();
        session.close();

        handle.onExit().get(5, TimeUnit.SECONDS);
        assertFalse(handle.isAlive());
        assertTrue(stage.isDone());
    }

    @Test
    void policyCannotRewriteWritePathOrContent() throws Exception {
        var mapper = new ObjectMapper();
        var client = new ScriptedModelClient(
                request -> assistant(List.of(new Content.ToolCall(
                                "write-1", "write", mapper.createObjectNode()
                                .put("path", "approved.txt")
                                .put("content", "approved"))),
                        StopReason.TOOL_CALL),
                request -> {
                    assertEquals("approved", readString(directory.resolve("approved.txt")));
                    assertFalse(Files.exists(directory.resolve("policy.txt")));
                    return assistant(List.of(new Content.Text("done")), StopReason.STOP);
                });
        CodingToolPolicy policy = (request, cancellation) -> {
            var observed = (ObjectNode) request.preparedArguments();
            observed.put("path", "policy.txt");
            observed.put("content", "rewritten");
            return CompletableFuture.completedStage(new CodingToolPolicy.Decision.Allow());
        };
        var tools = new CodingToolConfig(
                Set.of(CodingTool.WRITE), null, null, policy);

        try (var session = new CodingAgentSession(config(client, tools))) {
            session.prompt("write").toCompletableFuture().join();
        }
    }

    @Test
    void eventSinkFailureAfterCommitDoesNotRollBackFile() throws Exception {
        var mapper = new ObjectMapper();
        var client = new ScriptedModelClient(request -> assistant(List.of(new Content.ToolCall(
                        "write-1", "write", mapper.createObjectNode()
                        .put("path", "committed.txt")
                        .put("content", "committed"))),
                StopReason.TOOL_CALL));
        var tools = new CodingToolConfig(Set.of(CodingTool.WRITE), null, null, null);
        CodingAgentEventSink sink = event -> {
            if (event instanceof CodingAgentEvent.RuntimeEvent runtime
                    && runtime.event() instanceof AgentEvent.ToolCompleted) {
                return CompletableFuture.failedStage(new IllegalStateException("sink failed"));
            }
            return CompletableFuture.completedStage(null);
        };

        try (var session = new CodingAgentSession(config(client, tools, sink))) {
            assertThrows(CompletionException.class,
                    () -> session.prompt("write").toCompletableFuture().join());
        }

        assertEquals("committed", Files.readString(directory.resolve("committed.txt")));
    }

    private CodingAgentConfig config(ScriptedModelClient client, CodingToolConfig tools) {
        return config(client, tools, null);
    }

    private CodingAgentConfig config(
            ScriptedModelClient client,
            CodingToolConfig tools,
            CodingAgentEventSink eventSink
    ) {
        return new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                ThinkingLevel.PROVIDER_DEFAULT, ModelRequestOptions.defaults(),
                QueueMode.ONE_AT_A_TIME, QueueMode.ONE_AT_A_TIME,
                null, null, eventSink, Clock.fixed(NOW, ZoneOffset.UTC), tools);
    }

    private long awaitPid(Path pidFile) throws Exception {
        if (Files.isRegularFile(pidFile) && Files.size(pidFile) > 0) {
            return Long.parseLong(Files.readString(pidFile));
        }
        try (var watch = FileSystems.getDefault().newWatchService()) {
            directory.register(watch,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    fail("process did not write its pid");
                }
                var key = watch.poll(remaining, TimeUnit.NANOSECONDS);
                if (key == null) {
                    fail("process did not write its pid");
                }
                key.pollEvents();
                key.reset();
                if (Files.isRegularFile(pidFile) && Files.size(pidFile) > 0) {
                    return Long.parseLong(Files.readString(pidFile));
                }
            }
        }
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Message.Assistant assistant(List<Content> content, StopReason reason) {
        return new Message.Assistant(content, reason, null, Usage.zero(), NOW, MODEL);
    }
}
