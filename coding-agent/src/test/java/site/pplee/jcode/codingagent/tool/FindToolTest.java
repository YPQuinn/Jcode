package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FindToolTest {
    @TempDir
    Path directory;

    @Test
    void preparesDefaultsAndStrictlyRejectsInvalidArguments() {
        var tool = tool(new FakeSearchBackend());
        var factory = JsonNodeFactory.instance;

        var prepared = tool.prepareArguments(factory.objectNode().put("pattern", "**/*.java"));
        assertEquals(".", prepared.get("path").textValue());
        assertEquals(1_000, prepared.get("limit").intValue());

        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode()));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode().putNull("pattern")));
        assertDoesNotThrow(
                () -> tool.prepareArguments(factory.objectNode().put("pattern", "[ab]")));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode()
                        .put("pattern", "*").putNull("path")));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode()
                        .put("pattern", "*").put("limit", 2_001)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode()
                        .put("pattern", "*").put("extra", true)));
    }

    @Test
    void parsesChunkedNulRecordsAndReturnsJsonQuotedRelativePaths() {
        var backend = new FakeSearchBackend();
        byte[] output = "./src/Main.java\0./special\nname.java\0"
                .getBytes(StandardCharsets.UTF_8);
        backend.outputChunks.add(java.util.Arrays.copyOfRange(output, 0, 11));
        backend.outputChunks.add(java.util.Arrays.copyOfRange(output, 11, output.length));
        var tool = tool(backend);

        var result = execute(tool, new FindToolArguments("**/*.java", ".", 10));
        String text = ToolTestSupport.text(result);

        assertFalse(result.error());
        assertTrue(text.contains("\"src/Main.java\""));
        assertTrue(text.contains("\"special\\nname.java\""));
        assertFalse(text.contains("README.md"));
        assertEquals(directory.resolve(".").toAbsolutePath(), backend.workingDirectory);
        assertEquals(List.of(
                "--glob", "--color=never", "--hidden", "--print0", "--no-require-git",
                "--full-path", "--exclude", ".git", "--exclude", "node_modules", "--", "**/*.java", "."),
                backend.arguments);
    }

    @Test
    void omitsInvalidUtf8AndOversizedPathsThenContinues() {
        var backend = new FakeSearchBackend();
        byte[] oversized = ("x".repeat(SearchToolSupport.MAX_STRUCTURED_RECORD_BYTES + 1) + "\0")
                .getBytes(StandardCharsets.UTF_8);
        backend.outputChunks.add(concat(
                oversized,
                concat(new byte[]{(byte) 0xff, 0}, "./good.txt\0".getBytes(StandardCharsets.UTF_8))));
        var tool = tool(backend);

        var result = execute(tool, new FindToolArguments("*", ".", 10));
        String output = ToolTestSupport.text(result);

        assertFalse(result.error());
        assertTrue(output.contains("\"good.txt\""));
        assertTrue(output.contains("2 files omitted"));
    }

    @Test
    void unterminatedNulProtocolIsAnErrorAndPreservesCompletePaths() {
        var backend = new FakeSearchBackend();
        backend.outputChunks.add("./good.txt\0./private-partial"
                .getBytes(StandardCharsets.UTF_8));
        var tool = tool(backend);

        var result = execute(tool, new FindToolArguments("*", ".", 10));
        String output = ToolTestSupport.text(result);

        assertTrue(result.error());
        assertTrue(output.contains("\"good.txt\""));
        assertTrue(output.contains("malformed structured output"));
        assertFalse(output.contains("private-partial"));
    }

    @Test
    void fileLimitRequestsStopAndDoesNotConsumeLaterPaths() {
        var backend = new FakeSearchBackend();
        backend.outputChunks.add("./a\0./b\0./c\0".getBytes(StandardCharsets.UTF_8));
        var tool = tool(backend);

        var result = execute(tool, new FindToolArguments("*", ".", 2));
        String output = ToolTestSupport.text(result);

        assertFalse(result.error());
        assertTrue(output.contains("\"a\""));
        assertTrue(output.contains("\"b\""));
        assertFalse(output.contains("\"c\""));
        assertTrue(output.contains("File limit reached at 2"));
    }

    @Test
    void preservesSymlinkThenParentTraversalForSearchRoots() throws Exception {
        Path real = Files.createDirectories(directory.resolve("real"));
        Files.createDirectory(real.resolve("target"));
        Files.createDirectory(real.resolve("sibling"));
        try {
            Files.createSymbolicLink(directory.resolve("link"), Path.of("real/target"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;
        }
        var backend = new FakeSearchBackend();
        var tool = tool(backend);

        var result = execute(tool,
                new FindToolArguments("*", "link/../sibling", 10));

        assertFalse(result.error());
        assertEquals(directory.resolve("link/../sibling").toAbsolutePath(),
                backend.workingDirectory);
    }

    @Test
    void outputBudgetOmitsWholePathsWithoutReturningPartialIdentity() {
        var backend = new FakeSearchBackend();
        var bytes = new java.io.ByteArrayOutputStream();
        for (int index = 0; index < 30; index++) {
            String path = "./%02d-%s".formatted(index, "x".repeat(2_000));
            bytes.writeBytes(path.getBytes(StandardCharsets.UTF_8));
            bytes.write(0);
        }
        backend.outputChunks.add(bytes.toByteArray());
        var tool = tool(backend);

        var result = execute(tool, new FindToolArguments("*", ".", 100));
        String output = ToolTestSupport.text(result);
        String body = output.substring(0, output.indexOf("\n\n"));

        assertFalse(result.error());
        assertTrue(output.contains("Output byte limit reached"));
        assertTrue(output.getBytes(StandardCharsets.UTF_8).length
                <= SearchToolSupport.MAX_OUTPUT_BYTES + 2 + SearchToolSupport.MAX_STATUS_BYTES);
        for (String record : body.split("\n")) {
            assertTrue(record.startsWith("\""));
            assertTrue(record.endsWith("\""));
        }
    }

    @Test
    void requiresDirectoryAndPreservesBackendFailureReason() throws Exception {
        Files.writeString(directory.resolve("private-file"), "x");
        var tool = tool(new FakeSearchBackend());

        var invalidPath = execute(tool, new FindToolArguments("*", "private-file", 10));
        assertTrue(invalidPath.error());
        assertTrue(ToolTestSupport.text(invalidPath).contains("search path is not a directory"));
        assertFalse(ToolTestSupport.text(invalidPath).contains("private-file"));

        var backend = new FakeSearchBackend();
        backend.processResult = ProcessRunResult.exited(2);
        backend.standardError = "permission denied: /private/path";
        var failed = execute(tool(backend), new FindToolArguments("*", ".", 10));
        assertTrue(failed.error());
        assertTrue(ToolTestSupport.text(failed).contains("permission denied"));
        assertTrue(ToolTestSupport.text(failed).contains("/private/path"));
    }

    @Test
    void argumentToStringRedactsPatternAndPath() {
        var arguments = new FindToolArguments("*.secret", "private/path", 10);

        assertFalse(arguments.toString().contains("*.secret"));
        assertFalse(arguments.toString().contains("private/path"));
    }

    private FindTool tool(FakeSearchBackend backend) {
        return new FindTool(directory, backend);
    }

    private static site.pplee.jcode.agentcore.tool.ToolExecutionResult execute(
            FindTool tool,
            FindToolArguments arguments
    ) {
        return tool.execute(
                        "call", arguments, ToolUpdateSink.noop(), new MutableCancellationSignal())
                .toCompletableFuture().join();
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] combined = new byte[left.length + right.length];
        System.arraycopy(left, 0, combined, 0, left.length);
        System.arraycopy(right, 0, combined, left.length, right.length);
        return combined;
    }
}
