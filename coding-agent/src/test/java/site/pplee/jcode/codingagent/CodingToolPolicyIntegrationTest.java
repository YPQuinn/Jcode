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
import site.pplee.jcode.agentcore.queue.QueueMode;
import site.pplee.jcode.codingagent.support.ScriptedModelClient;
import site.pplee.jcode.codingagent.tool.BashConfig;
import site.pplee.jcode.codingagent.tool.CodingTool;
import site.pplee.jcode.codingagent.tool.CodingToolConfig;
import site.pplee.jcode.codingagent.tool.CodingToolPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class CodingToolPolicyIntegrationTest {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "model");
    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");

    @TempDir
    Path directory;

    @Test
    void policyReceivesSnapshotAndCannotRewriteExecutionArguments() throws Exception {
        Files.writeString(directory.resolve("allowed.txt"), "expected\n");
        var client = readThenAssertResult(false, "expected\n");
        CodingToolPolicy policy = (request, cancellation) -> {
            assertEquals("call-1", request.toolCallId());
            assertEquals("read", request.toolName());
            assertEquals(directory.toAbsolutePath().normalize(), request.workingDirectory());
            ((ObjectNode) request.preparedArguments()).put("path", "missing.txt");
            return CompletableFuture.completedStage(new CodingToolPolicy.Decision.Allow());
        };

        try (var session = new CodingAgentSession(config(client,
                new CodingToolConfig(
                        CodingToolConfig.readOnly().enabledTools(), null, null, policy)))) {
            var result = session.prompt("read it").toCompletableFuture().join();
            assertEquals("done", ((Content.Text) result.finalMessage().content().getFirst()).text());
        }
    }

    @Test
    void deniedPolicyBecomesOrdinaryToolErrorAndDoesNotExecuteTool() {
        var client = readThenAssertResult(true, "blocked by before hook: not approved");
        CodingToolPolicy policy = (request, cancellation) -> CompletableFuture.completedStage(
                new CodingToolPolicy.Decision.Deny("not approved"));

        try (var session = new CodingAgentSession(config(client,
                new CodingToolConfig(
                        CodingToolConfig.readOnly().enabledTools(), null, null, policy)))) {
            session.prompt("read it").toCompletableFuture().join();
        }
    }

    @Test
    void policyFailuresAreNotTreatedAsApproval() {
        assertPolicyFailure((request, cancellation) -> {
            throw new IllegalStateException("policy exploded");
        }, "before hook failed: policy exploded");
        assertPolicyFailure((request, cancellation) -> CompletableFuture.failedStage(
                new IllegalStateException("async policy exploded")),
                "before hook failed: async policy exploded");
        assertPolicyFailure((request, cancellation) -> null,
                "before hook returned null stage");
        assertPolicyFailure((request, cancellation) -> CompletableFuture.completedStage(null),
                "before hook returned null decision");
    }

    @Test
    void explicitEmptySelectionKeepsPromptAndRequestInSync() {
        var client = new ScriptedModelClient(request -> {
            assertTrue(request.tools().isEmpty());
            assertTrue(request.systemPrompt().contains("Available tools:\n(none)"));
            return assistant(List.of(new Content.Text("done")), StopReason.STOP);
        });
        var tools = new CodingToolConfig(Set.of(), null, null, null);

        try (var session = new CodingAgentSession(config(client, tools))) {
            session.prompt("hello").toCompletableFuture().join();
        }
    }

    @Test
    void enabledExternalToolConfigurationFailsFastDuringSessionAssembly() {
        var client = new ScriptedModelClient();
        var missingBashConfig = new CodingToolConfig(
                Set.of(CodingTool.BASH), null, null, null);
        var missingSearchConfig = new CodingToolConfig(
                Set.of(CodingTool.GREP), null, null, null);
        var missingExecutable = new CodingToolConfig(
                Set.of(CodingTool.BASH),
                new BashConfig(directory.resolve("missing-bash"), Map.of(),
                        Duration.ofSeconds(1), Duration.ofSeconds(2)),
                null, null);

        var missingConfigFailure = assertThrows(IllegalArgumentException.class,
                () -> new CodingAgentSession(config(client, missingBashConfig)));
        assertTrue(missingConfigFailure.getMessage().contains("bash configuration is required"));
        var missingSearchFailure = assertThrows(IllegalArgumentException.class,
                () -> new CodingAgentSession(config(client, missingSearchConfig)));
        assertTrue(missingSearchFailure.getMessage().contains("search configuration is required"));
        var missingExecutableFailure = assertThrows(IllegalArgumentException.class,
                () -> new CodingAgentSession(config(client, missingExecutable)));
        assertTrue(missingExecutableFailure.getMessage().contains("not a regular file"));
    }

    @Test
    void oldConfigConstructorKeepsReadOnlyDefault() {
        var client = new ScriptedModelClient(request -> {
            assertEquals(List.of("read"), request.tools().stream().map(tool -> tool.name()).toList());
            assertTrue(request.systemPrompt().contains("- read:"));
            assertFalse(request.systemPrompt().contains("- write:"));
            return assistant(List.of(new Content.Text("done")), StopReason.STOP);
        });

        var oldShape = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                ThinkingLevel.PROVIDER_DEFAULT, ModelRequestOptions.defaults(),
                QueueMode.ONE_AT_A_TIME, QueueMode.ONE_AT_A_TIME,
                null, null, null, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(CodingToolConfig.readOnly().enabledTools(), oldShape.tools().enabledTools());
        var canonicalWithNullTools = new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                ThinkingLevel.PROVIDER_DEFAULT, ModelRequestOptions.defaults(),
                QueueMode.ONE_AT_A_TIME, QueueMode.ONE_AT_A_TIME,
                null, null, null, Clock.fixed(NOW, ZoneOffset.UTC), null);
        assertEquals(CodingToolConfig.readOnly().enabledTools(),
                canonicalWithNullTools.tools().enabledTools());
        try (var session = new CodingAgentSession(oldShape)) {
            session.prompt("hello").toCompletableFuture().join();
        }
    }

    private void assertPolicyFailure(CodingToolPolicy policy, String expectedMessage) {
        var client = readThenAssertResult(true, expectedMessage);
        try (var session = new CodingAgentSession(config(client,
                new CodingToolConfig(
                        CodingToolConfig.readOnly().enabledTools(), null, null, policy)))) {
            session.prompt("read it").toCompletableFuture().join();
        }
    }

    private ScriptedModelClient readThenAssertResult(boolean error, String expectedText) {
        return new ScriptedModelClient(
                request -> assistant(List.of(new Content.ToolCall(
                                "call-1", "read",
                                new ObjectMapper().createObjectNode().put("path", "allowed.txt"))),
                        StopReason.TOOL_CALL),
                request -> {
                    var result = assertInstanceOf(Message.ToolResultMessage.class,
                            request.messages().getLast());
                    assertEquals(error, result.error());
                    assertTrue(assertInstanceOf(Content.Text.class,
                                    result.content().getFirst()).text().contains(expectedText));
                    return assistant(List.of(new Content.Text("done")), StopReason.STOP);
                });
    }

    private CodingAgentConfig config(ScriptedModelClient client, CodingToolConfig tools) {
        return new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                ThinkingLevel.PROVIDER_DEFAULT, ModelRequestOptions.defaults(),
                QueueMode.ONE_AT_A_TIME, QueueMode.ONE_AT_A_TIME,
                null, null, null, Clock.fixed(NOW, ZoneOffset.UTC), tools);
    }

    private static Message.Assistant assistant(List<Content> content, StopReason reason) {
        return new Message.Assistant(content, reason, null, Usage.zero(), NOW, MODEL);
    }
}
