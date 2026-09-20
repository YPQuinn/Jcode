package site.pplee.jcode.codingagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.codingagent.support.NativeToolTestSupport;
import site.pplee.jcode.codingagent.support.ScriptedModelClient;
import site.pplee.jcode.codingagent.tool.BashConfig;
import site.pplee.jcode.codingagent.tool.CodingTool;
import site.pplee.jcode.codingagent.tool.CodingToolConfig;
import site.pplee.jcode.codingagent.tool.CodingToolPolicy;
import site.pplee.jcode.codingagent.tool.SearchConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalToolsWorkspaceIntegrationTest {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "model");
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");

    @TempDir
    Path directory;

    @Test
    void allToolsCompleteARealGitWorkspaceDiscoveryMutationAndRetryLoop() throws Exception {
        NativeToolTestSupport.requirePosixProcessSupport();
        Path bash = NativeToolTestSupport.requireBash();
        Path ripgrep = NativeToolTestSupport.requireRipgrep();
        createGitWorkspace();

        var mapper = new ObjectMapper();
        var client = new ScriptedModelClient(
                request -> {
                    assertAllToolsExposed(request);
                    return toolCalls(
                            call("ls-1", "ls", mapper.createObjectNode()
                                    .put("path", ".").put("limit", 20)),
                            call("find-1", "find", mapper.createObjectNode()
                                    .put("pattern", "**/*.java").put("path", ".").put("limit", 20)),
                            call("grep-1", "grep", mapper.createObjectNode()
                                    .put("pattern", "TARGET").put("path", ".")
                                    .put("glob", "*.java").put("literal", true).put("limit", 20)));
                },
                request -> {
                    assertSuccessfulText(request, "ls-1", "src");
                    assertSuccessfulText(request, "find-1", "src/App.java");
                    assertSuccessfulText(request, "grep-1", "TARGET");
                    return toolCalls(call("read-1", "read", mapper.createObjectNode()
                            .put("path", "src/App.java")));
                },
                request -> {
                    assertSuccessfulText(request, "read-1", "before");
                    var edit = mapper.createObjectNode().put("path", "src/App.java");
                    edit.putArray("edits").add(mapper.createObjectNode()
                            .put("oldText", "return \"before\";")
                            .put("newText", "return \"wrong\";"));
                    return toolCalls(
                            call("edit-wrong", "edit", edit),
                            call("write-check", "write", mapper.createObjectNode()
                                    .put("path", "verify.sh")
                                    .put("content", verificationScript())));
                },
                request -> {
                    assertSuccessfulText(request, "edit-wrong", "Applied");
                    assertSuccessfulText(request, "write-check", "Wrote");
                    return toolCalls(call("bash-fail", "bash", bashArguments(mapper)));
                },
                request -> {
                    assertFailedText(request, "bash-fail", "FAIL");
                    var edit = mapper.createObjectNode().put("path", "src/App.java");
                    edit.putArray("edits").add(mapper.createObjectNode()
                            .put("oldText", "return \"wrong\";")
                            .put("newText", "return \"fixed\";"));
                    return toolCalls(call("edit-fixed", "edit", edit));
                },
                request -> {
                    assertSuccessfulText(request, "edit-fixed", "Applied");
                    return toolCalls(call("bash-pass", "bash", bashArguments(mapper)));
                },
                request -> {
                    assertSuccessfulText(request, "bash-pass", "PASS");
                    return assistant(List.of(new Content.Text("workspace validated")), StopReason.STOP);
                });

        var tools = CodingToolConfig.codingWithSearch(
                new BashConfig(bash, Map.of(), Duration.ofSeconds(5), Duration.ofSeconds(20)),
                new SearchConfig(ripgrep, Map.of()));

        try (var session = new CodingAgentSession(config(client, tools))) {
            var result = session.prompt("locate, update, and verify the project")
                    .toCompletableFuture().join();
            assertEquals("workspace validated", assertInstanceOf(
                    Content.Text.class, result.finalMessage().content().getFirst()).text());
        }

        assertTrue(Files.readString(directory.resolve("src/App.java")).contains("return \"fixed\";"));
        assertEquals(7, client.requests().size());
        assertRecordedProcessesExited();
        assertNoMutationTemporaryFiles();
    }

    @Test
    void recoverablePolicyEditAndSearchFailuresDoNotPoisonTheSession() throws Exception {
        Path bash = NativeToolTestSupport.requireBash();
        Path ripgrep = NativeToolTestSupport.requireRipgrep();
        createGitWorkspace();
        var mapper = new ObjectMapper();
        CodingToolPolicy policy = (request, cancellation) -> java.util.concurrent.CompletableFuture
                .completedStage(request.toolCallId().equals("write-denied")
                        ? new CodingToolPolicy.Decision.Deny("not approved")
                        : new CodingToolPolicy.Decision.Allow());

        var client = new ScriptedModelClient(
                request -> {
                    var conflictingEdit = mapper.createObjectNode().put("path", "src/App.java");
                    conflictingEdit.putArray("edits").add(mapper.createObjectNode()
                            .put("oldText", "missing source text")
                            .put("newText", "unused"));
                    return toolCalls(
                            call("write-denied", "write", mapper.createObjectNode()
                                    .put("path", "denied.txt").put("content", "denied")),
                            call("edit-conflict", "edit", conflictingEdit),
                            call("find-missing", "find", mapper.createObjectNode()
                                    .put("pattern", "*.java")
                                    .put("path", "missing-directory").put("limit", 10)));
                },
                request -> {
                    assertFailedText(request, "write-denied", "not approved");
                    assertFailedText(request, "edit-conflict", "was not found");
                    assertFailedText(request, "find-missing", "does not exist");
                    return toolCalls(call("write-recovery", "write", mapper.createObjectNode()
                            .put("path", "recovered.txt").put("content", "recovered")));
                },
                request -> {
                    assertSuccessfulText(request, "write-recovery", "Wrote");
                    return assistant(List.of(new Content.Text("recovered")), StopReason.STOP);
                });
        var tools = new CodingToolConfig(
                EnumSet.allOf(CodingTool.class),
                new BashConfig(bash, Map.of(), Duration.ofSeconds(5), Duration.ofSeconds(20)),
                new SearchConfig(ripgrep, Map.of()),
                policy);

        try (var session = new CodingAgentSession(config(client, tools))) {
            var result = session.prompt("recover from tool failures").toCompletableFuture().join();
            assertEquals("recovered", assertInstanceOf(
                    Content.Text.class, result.finalMessage().content().getFirst()).text());
        }

        assertFalse(Files.exists(directory.resolve("denied.txt")));
        assertEquals("recovered", Files.readString(directory.resolve("recovered.txt")));
        assertTrue(Files.readString(directory.resolve("src/App.java")).contains("before"));
    }

    private void createGitWorkspace() throws IOException {
        Files.createDirectories(directory.resolve(".git"));
        Files.createDirectories(directory.resolve("src"));
        Files.writeString(directory.resolve(".gitignore"), "ignored.java\n");
        Files.writeString(directory.resolve("src/App.java"), """
                final class App {
                    String value() {
                        return "before"; // TARGET
                    }
                }
                """);
        Files.writeString(directory.resolve("ignored.java"), "// TARGET\n");
    }

    private static JsonNode bashArguments(ObjectMapper mapper) {
        return mapper.createObjectNode()
                .put("command", "printf '%s\\n' \"$$\" >> .jcode-pids; . ./verify.sh")
                .put("timeout", 5);
    }

    private static String verificationScript() {
        return """
                if [[ "$(<src/App.java)" == *'return "fixed";'* ]]; then
                    printf 'PASS\\n'
                    exit 0
                fi
                printf 'FAIL\\n'
                exit 1
                """;
    }

    private static Content.ToolCall call(String id, String name, JsonNode arguments) {
        return new Content.ToolCall(id, name, arguments);
    }

    private static Message.Assistant toolCalls(Content.ToolCall... calls) {
        return assistant(List.<Content>of(calls), StopReason.TOOL_CALL);
    }

    private static void assertAllToolsExposed(ModelRequest request) {
        assertEquals(
                List.of("read", "write", "edit", "bash", "grep", "find", "ls"),
                request.tools().stream().map(tool -> tool.name()).toList());
        for (var tool : request.tools()) {
            assertTrue(request.systemPrompt().contains("- " + tool.name() + ":"));
        }
    }

    private static void assertSuccessfulText(ModelRequest request, String id, String expected) {
        var result = toolResult(request, id);
        assertFalse(result.error(), () -> "expected successful tool result for " + id);
        assertTrue(resultText(result).contains(expected),
                () -> "expected tool result for " + id + " to contain " + expected);
    }

    private static void assertFailedText(ModelRequest request, String id, String expected) {
        var result = toolResult(request, id);
        assertTrue(result.error(), () -> "expected failed tool result for " + id);
        assertTrue(resultText(result).contains(expected),
                () -> "expected tool result for " + id + " to contain " + expected);
    }

    private static Message.ToolResultMessage toolResult(ModelRequest request, String id) {
        return request.messages().stream()
                .filter(Message.ToolResultMessage.class::isInstance)
                .map(Message.ToolResultMessage.class::cast)
                .filter(result -> result.toolCallId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing tool result for " + id));
    }

    private static String resultText(Message.ToolResultMessage result) {
        return result.content().stream()
                .filter(Content.Text.class::isInstance)
                .map(Content.Text.class::cast)
                .map(Content.Text::text)
                .findFirst()
                .orElseThrow();
    }

    private CodingAgentConfig config(ScriptedModelClient client, CodingToolConfig tools) {
        return new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                ThinkingLevel.PROVIDER_DEFAULT, ModelRequestOptions.defaults(),
                QueueMode.ONE_AT_A_TIME, QueueMode.ONE_AT_A_TIME,
                null, null, null, Clock.fixed(NOW, ZoneOffset.UTC), tools);
    }

    private void assertRecordedProcessesExited() throws IOException {
        var pids = Files.readAllLines(directory.resolve(".jcode-pids")).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .mapToLong(Long::parseLong)
                .toArray();
        assertEquals(2, pids.length);
        for (long pid : pids) {
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    () -> "owned process remains alive: " + pid);
        }
    }

    private void assertNoMutationTemporaryFiles() throws IOException {
        try (var paths = Files.walk(directory)) {
            assertTrue(paths.noneMatch(path -> {
                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                return name.startsWith(".jcode-") && name.endsWith(".tmp");
            }));
        }
    }

    private static Message.Assistant assistant(List<Content> content, StopReason reason) {
        return new Message.Assistant(content, reason, null, Usage.zero(), NOW, MODEL);
    }
}
