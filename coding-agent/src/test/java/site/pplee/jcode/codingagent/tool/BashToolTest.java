package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {
    @TempDir
    Path directory;

    private BashTool tool;

    @AfterEach
    void closeTool() {
        if (tool != null) {
            tool.close();
        }
    }

    @Test
    void preparesDefaultsAndRejectsLooseOrOversizedArguments() {
        tool = tool(new FakeProcessExecutor(ProcessRunResult.exited(0)));
        var factory = JsonNodeFactory.instance;

        var prepared = tool.prepareArguments(factory.objectNode().put("command", "printf ok"));
        assertEquals(5, prepared.get("timeout").longValue());
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode()
                        .put("command", "true").put("timeout", 11)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode()
                        .put("command", "true").put("timeout", 1.5)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode()
                        .put("command", "true").putNull("timeout")));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode()
                        .put("command", "true").put("extra", true)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode()
                        .put("command", "x".repeat(BashTool.MAX_COMMAND_BYTES + 1))));
    }

    @Test
    void launchesConfiguredBashWithoutProfilesAndWithAnEnvironmentSnapshot() {
        var executor = new FakeProcessExecutor(ProcessRunResult.exited(0));
        executor.output = "ok\n".getBytes(StandardCharsets.UTF_8);
        tool = tool(executor);

        var result = tool.execute(
                        "call",
                        new BashToolArguments("printf ok", 7L),
                        ToolUpdateSink.noop(),
                        new MutableCancellationSignal())
                .toCompletableFuture().join();

        assertFalse(result.error());
        assertEquals("ok\n\n[Process exited with code 0]", ToolTestSupport.text(result));
        assertEquals(directory.toAbsolutePath().normalize(), executor.request.workingDirectory());
        assertEquals(
                java.util.List.of("/configured/bash", "--noprofile", "--norc", "-c", "printf ok"),
                executor.request.command());
        assertEquals(Map.of("TOKEN", "value"), executor.request.environment());
        assertEquals(Duration.ofSeconds(7), executor.request.timeout());
        assertEquals(ProcessRequest.OutputMode.MERGED, executor.request.outputMode());
        assertFalse(executor.request.toString().contains("printf ok"));
        assertFalse(executor.request.toString().contains("TOKEN"));
    }

    @Test
    void mapsNonZeroTimeoutCancellationAndStartFailureToRecoverableToolErrors() {
        assertOutcome(ProcessRunResult.exited(9), "Process exited with code 9");
        assertOutcome(ProcessRunResult.timedOut(), "Process timed out after 5 seconds");
        assertOutcome(ProcessRunResult.cancelled(), "Process cancelled");
        assertOutcome(ProcessRunResult.startFailed(), "Process could not be started");
        assertOutcome(ProcessRunResult.resourceBusy(), "Process capacity is exhausted");
        assertOutcome(ProcessRunResult.failed("output collection failed"), "output collection failed");
    }

    @Test
    void outputTailAndDiagnosticsHaveIndependentBounds() {
        var executor = new FakeProcessExecutor(ProcessRunResult.exited(3));
        executor.output = ("line\n".repeat(BashTool.MAX_OUTPUT_LINES + 100))
                .getBytes(StandardCharsets.UTF_8);
        tool = tool(executor);

        var result = tool.execute(
                        "call", new BashToolArguments("generate", 5L),
                        ToolUpdateSink.noop(), new MutableCancellationSignal())
                .toCompletableFuture().join();
        String text = ToolTestSupport.text(result);

        assertTrue(result.error());
        assertTrue(text.contains("Output truncated"));
        assertTrue(text.endsWith("[Process exited with code 3]"));
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length
                <= BashTool.MAX_OUTPUT_BYTES + BashTool.MAX_STATUS_BYTES);
    }

    @Test
    void updateSinkFailureRemainsAnInfrastructureFailure() {
        var executor = new FakeProcessExecutor(ProcessRunResult.exited(0));
        executor.output = "update".getBytes(StandardCharsets.UTF_8);
        tool = tool(executor);

        var failure = assertThrows(IllegalArgumentException.class, () -> tool.execute(
                "call",
                new BashToolArguments("run", 5L),
                update -> {
                    throw new IllegalArgumentException("sink failed");
                },
                new MutableCancellationSignal()));

        assertEquals("sink failed", failure.getMessage());
    }

    @Test
    void failedUpdateCancelsTheProcessBeforeItsExecutionReturns() {
        var stopped = new CompletableFuture<Void>();
        var sinkFailure = new IllegalStateException("sink failed while process was running");
        var callerCancellation = new MutableCancellationSignal();
        tool = tool(new ProcessExecutor() {
            @Override
            public ProcessRunResult run(
                    ProcessRequest request, ProcessOutputConsumer output,
                    CancellationSignal cancellation
            ) {
                try (var registration = cancellation.onCancellation(() -> stopped.complete(null))) {
                    byte[] bytes = "ready\n".getBytes(StandardCharsets.UTF_8);
                    output.accept(ProcessOutputChannel.MERGED, bytes, 0, bytes.length);
                    try {
                        stopped.get(2, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        return ProcessRunResult.exited(0);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    } catch (ExecutionException e) {
                        throw new AssertionError(e);
                    }
                    return ProcessRunResult.cancelled();
                }
            }

            @Override
            public void close() {
            }
        });

        var failure = assertThrows(CompletionException.class, () -> tool.execute(
                "call", new BashToolArguments("run", 5L),
                update -> CompletableFuture.failedFuture(sinkFailure),
                callerCancellation));
        assertSame(sinkFailure, failure.getCause());
        assertTrue(stopped.isDone(), "sink failure must stop the process before it returns");
        assertFalse(callerCancellation.isCancelled(), "the tool must own its cancellation source");
    }

    @Test
    void argumentRecordAndToolUseSequentialExecution() throws Exception {
        var mapper = new ObjectMapper();
        tool = tool(new FakeProcessExecutor(ProcessRunResult.exited(0)));

        var arguments = mapper.treeToValue(
                mapper.createObjectNode().put("command", "true").put("timeout", 5),
                BashToolArguments.class);

        assertEquals("true", arguments.command());
        assertEquals(5L, arguments.timeout());
        assertFalse(arguments.toString().contains("true"));
        assertEquals(ToolExecutionMode.SEQUENTIAL, tool.executionMode());
        assertEquals("bash", tool.name());
        assertEquals("object", tool.parametersSchema().get("type").textValue());
    }

    private void assertOutcome(ProcessRunResult outcome, String expected) {
        var executor = new FakeProcessExecutor(outcome);
        executor.output = "partial".getBytes(StandardCharsets.UTF_8);
        tool = tool(executor);

        var result = tool.execute(
                        "call", new BashToolArguments("run", 5L),
                        ToolUpdateSink.noop(), new MutableCancellationSignal())
                .toCompletableFuture().join();

        assertTrue(result.error());
        assertTrue(ToolTestSupport.text(result).contains("partial"));
        assertTrue(ToolTestSupport.text(result).contains(expected));
        tool.close();
        tool = null;
    }

    private BashTool tool(ProcessExecutor executor) {
        var config = new BashConfig(
                Path.of("/configured/bash"), Map.of("TOKEN", "value"),
                Duration.ofSeconds(5), Duration.ofSeconds(10));
        return new BashTool(directory, config, executor);
    }

    private static final class FakeProcessExecutor implements ProcessExecutor {
        private final ProcessRunResult result;
        private ProcessRequest request;
        private byte[] output = new byte[0];

        private FakeProcessExecutor(ProcessRunResult result) {
            this.result = result;
        }

        @Override
        public ProcessRunResult run(
                ProcessRequest request,
                ProcessOutputConsumer output,
                CancellationSignal cancellation
        ) {
            this.request = request;
            output.accept(ProcessOutputChannel.MERGED, this.output, 0, this.output.length);
            return result;
        }

        @Override
        public void close() {
        }
    }
}
