package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GrepToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void preparesDefaultsAndStrictlyRejectsInvalidArguments() {
        var tool = tool(new FakeSearchBackend());
        var factory = JsonNodeFactory.instance;

        var prepared = tool.prepareArguments(factory.objectNode().put("pattern", "needle"));
        assertEquals(".", prepared.get("path").textValue());
        assertFalse(prepared.get("ignoreCase").booleanValue());
        assertFalse(prepared.get("literal").booleanValue());
        assertEquals(100, prepared.get("limit").intValue());

        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode()));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode().putNull("pattern")));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode().put("pattern", "")));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode()
                        .put("pattern", "x").putNull("glob")));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode()
                        .put("pattern", "x").put("ignoreCase", "yes")));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode()
                        .put("pattern", "x").put("limit", 1_001)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode()
                        .put("pattern", "x").put("extra", true)));
    }

    @Test
    void usesArgumentVectorAndPostFiltersIgnoreAwareResults() throws Exception {
        Files.writeString(directory.resolve("-input.txt"), "-Needle\n");
        var backend = new FakeSearchBackend();
        backend.outputChunks.add(events(
                matchText("-input.txt", 1, "-Needle\n"),
                matchText("other.java", 2, "-Needle\n")));
        var tool = tool(backend);

        var result = execute(tool, new GrepToolArguments(
                "-needle", "-input.txt", "*.txt", true, true, 10));

        assertFalse(result.error());
        assertTrue(ToolTestSupport.text(result).contains("\"-input.txt\":1: \"-Needle\""));
        assertFalse(ToolTestSupport.text(result).contains("other.java"));
        assertEquals(directory.toAbsolutePath().normalize(), backend.workingDirectory);
        assertEquals(List.of(
                "--json", "--no-config", "--no-ignore-global",
                "--threads", "2", "--max-filesize", "8M",
                "--ignore-case", "--fixed-strings",
                "-e", "-needle", "--", "./-input.txt"), backend.arguments);
    }

    @Test
    void checksExplicitFileSizeBeforeStartingBackendAndAcceptsTheBoundary() throws Exception {
        var backend = new FakeSearchBackend();
        var tool = tool(backend);
        Path path = directory.resolve("large.txt");
        try (var file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(SearchToolSupport.MAX_FILE_BYTES + 1L);
            var rejected = execute(tool,
                    new GrepToolArguments("needle", "large.txt", null, false, true, 10));
            assertTrue(rejected.error());
            assertTrue(ToolTestSupport.text(rejected).contains("8 MiB"));
            assertNull(backend.arguments, "oversized input must not reach the backend");

            file.setLength(SearchToolSupport.MAX_FILE_BYTES);
            var accepted = execute(tool,
                    new GrepToolArguments("needle", "large.txt", null, false, true, 10));
            assertFalse(accepted.error());
            assertNotNull(backend.arguments);
        }
    }

    @Test
    void prefixesAnExplicitDashFileSoItCannotSelectStdin() throws Exception {
        Files.writeString(directory.resolve("-"), "needle\n");
        var backend = new FakeSearchBackend();
        var result = execute(tool(backend),
                new GrepToolArguments("needle", "-", null, false, true, 10));
        assertFalse(result.error());
        assertEquals("./-", backend.arguments.getLast());
    }

    @Test
    void decodesBytesFieldsAndSafelyFormatsSpecialPathsAndLongSummaries() {
        var backend = new FakeSearchBackend();
        String path = "special:name\nfile.txt";
        String line = "😀".repeat(600) + "\n";
        backend.outputChunks.add(events(matchBytes(path, 7, line)));
        var tool = tool(backend);

        var result = execute(tool, new GrepToolArguments("x", ".", null, false, false, 10));
        String output = ToolTestSupport.text(result);

        assertFalse(result.error());
        assertTrue(output.contains("\"special:name\\nfile.txt\":7:"));
        assertTrue(output.contains("… [truncated]"));
        String summaryJson = output.substring(output.indexOf(":7: ") + 4, output.indexOf("\n\n"));
        String summary = assertDoesNotThrow(() -> mapper.readValue(summaryJson, String.class));
        assertEquals(GrepTool.MAX_LINE_CODE_POINTS,
                summary.codePointCount(0, summary.length()));
    }

    @Test
    void malformedOutputAfterPartialResultsIsAnErrorWithoutEchoingRawRecord() {
        var backend = new FakeSearchBackend();
        backend.outputChunks.add(concat(
                events(matchText("ok.txt", 1, "found\n")),
                "private malformed payload\n".getBytes(StandardCharsets.UTF_8)));
        var tool = tool(backend);

        var result = execute(tool, new GrepToolArguments("found", ".", null, false, false, 10));
        String output = ToolTestSupport.text(result);

        assertTrue(result.error());
        assertTrue(output.contains("\"ok.txt\":1: \"found\""));
        assertTrue(output.contains("Search failed: search backend returned malformed structured output"));
        assertFalse(output.contains("private malformed payload"));
    }

    @Test
    void oversizedAndInvalidUtf8RecordsAreOmittedWithoutLosingLaterMatches() {
        var backend = new FakeSearchBackend();
        byte[] oversized = ("x".repeat(SearchToolSupport.MAX_STRUCTURED_RECORD_BYTES + 1) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        backend.outputChunks.add(concat(
                oversized,
                events(matchBytes(new byte[]{(byte) 0xff}, 1, "bad\n"),
                        matchText("good.txt", 2, "good\n"))));
        var tool = tool(backend);

        var result = execute(tool, new GrepToolArguments("good", ".", null, false, false, 10));
        String output = ToolTestSupport.text(result);

        assertFalse(result.error());
        assertTrue(output.contains("\"good.txt\":2: \"good\""));
        assertTrue(output.contains("2 matches omitted"));
    }

    @Test
    void matchLimitRequestsExpectedBackendStop() {
        var backend = new FakeSearchBackend();
        backend.outputChunks.add(events(
                matchText("a", 1, "x\n"),
                matchText("b", 2, "x\n"),
                matchText("c", 3, "x\n")));
        var tool = tool(backend);

        var result = execute(tool, new GrepToolArguments("x", ".", null, false, false, 2));
        String output = ToolTestSupport.text(result);

        assertFalse(result.error());
        assertTrue(output.contains("\"a\":1"));
        assertTrue(output.contains("\"b\":2"));
        assertFalse(output.contains("\"c\":3"));
        assertTrue(output.contains("Match limit reached at 2"));
    }

    @Test
    void mapsNoMatchesBackendErrorsAndCancellationWithoutLeakingDiagnostics() {
        var noMatches = new FakeSearchBackend();
        noMatches.processResult = ProcessRunResult.exited(1);
        var noMatchResult = execute(tool(noMatches),
                new GrepToolArguments("none", ".", null, false, false, 10));
        assertFalse(noMatchResult.error());
        assertTrue(ToolTestSupport.text(noMatchResult).startsWith("(no matches)"));

        var invalidPattern = new FakeSearchBackend();
        invalidPattern.processResult = ProcessRunResult.exited(2);
        invalidPattern.standardError = "regex parse error near private-pattern";
        var invalidResult = execute(tool(invalidPattern),
                new GrepToolArguments("[", ".", null, false, false, 10));
        assertTrue(invalidResult.error());
        assertTrue(ToolTestSupport.text(invalidResult).contains("invalid search pattern"));
        assertFalse(ToolTestSupport.text(invalidResult).contains("private-pattern"));

        var cancelled = new FakeSearchBackend();
        cancelled.stopOrigin = SearchBackend.StopOrigin.CALLER_CANCELLATION;
        cancelled.processResult = ProcessRunResult.cancelled();
        var cancelledResult = execute(tool(cancelled),
                new GrepToolArguments("x", ".", null, false, false, 10));
        assertTrue(cancelledResult.error());
        assertTrue(ToolTestSupport.text(cancelledResult).contains("search cancelled"));
    }

    @Test
    void backendExitTwoAfterAResultLimitRemainsAnError() {
        var backend = new FakeSearchBackend();
        backend.outputChunks.add(events(matchText("partial.txt", 1, "x\n")));
        backend.stopOrigin = SearchBackend.StopOrigin.COLLECTOR;
        backend.processResult = ProcessRunResult.exited(2);
        backend.standardError = "permission denied: /private/path";

        var result = execute(tool(backend),
                new GrepToolArguments("x", ".", null, false, false, 1));

        assertTrue(result.error());
        assertTrue(ToolTestSupport.text(result).contains("partial.txt"));
        assertTrue(ToolTestSupport.text(result).contains("search path could not be read"));
        assertFalse(ToolTestSupport.text(result).contains("/private/path"));
    }

    @Test
    void mapsProcessInfrastructureFailuresToBoundedToolErrors() {
        assertProcessFailure(ProcessRunResult.timedOut(), "timed out after 30 seconds");
        assertProcessFailure(ProcessRunResult.startFailed(), "could not be started");
        assertProcessFailure(ProcessRunResult.resourceBusy(), "capacity is exhausted");
        assertProcessFailure(ProcessRunResult.failed("private failure"), "search process failed");
        assertProcessFailure(ProcessRunResult.terminationFailed(), "could not be terminated");
    }

    @Test
    void argumentToStringRedactsPatternPathAndGlob() {
        var arguments = new GrepToolArguments(
                "secret-pattern", "private/path", "*.private", false, false, 10);

        assertFalse(arguments.toString().contains("secret-pattern"));
        assertFalse(arguments.toString().contains("private/path"));
        assertFalse(arguments.toString().contains("*.private"));
    }

    private void assertProcessFailure(ProcessRunResult processResult, String expected) {
        var backend = new FakeSearchBackend();
        backend.processResult = processResult;
        var result = execute(tool(backend),
                new GrepToolArguments("x", ".", null, false, false, 10));

        assertTrue(result.error());
        assertTrue(ToolTestSupport.text(result).contains(expected));
        assertFalse(ToolTestSupport.text(result).contains("private failure"));
    }

    private GrepTool tool(FakeSearchBackend backend) {
        return new GrepTool(directory, backend);
    }

    private static site.pplee.jcode.agentcore.tool.ToolExecutionResult execute(
            GrepTool tool,
            GrepToolArguments arguments
    ) {
        return tool.execute(
                        "call", arguments, ToolUpdateSink.noop(), new MutableCancellationSignal())
                .toCompletableFuture().join();
    }

    private byte[] events(com.fasterxml.jackson.databind.node.ObjectNode... events) {
        var output = new StringBuilder();
        for (var event : events) {
            output.append(event).append('\n');
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode matchText(
            String path,
            long lineNumber,
            String line
    ) {
        var data = mapper.createObjectNode();
        data.set("path", mapper.createObjectNode().put("text", path));
        data.set("lines", mapper.createObjectNode().put("text", line));
        data.put("line_number", lineNumber);
        return mapper.createObjectNode().put("type", "match").set("data", data);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode matchBytes(
            String path,
            long lineNumber,
            String line
    ) {
        return matchBytes(path.getBytes(StandardCharsets.UTF_8), lineNumber, line);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode matchBytes(
            byte[] path,
            long lineNumber,
            String line
    ) {
        var data = mapper.createObjectNode();
        data.set("path", mapper.createObjectNode()
                .put("bytes", Base64.getEncoder().encodeToString(path)));
        data.set("lines", mapper.createObjectNode()
                .put("bytes", Base64.getEncoder().encodeToString(
                        line.getBytes(StandardCharsets.UTF_8))));
        data.put("line_number", lineNumber);
        return mapper.createObjectNode().put("type", "match").set("data", data);
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] combined = new byte[left.length + right.length];
        System.arraycopy(left, 0, combined, 0, left.length);
        System.arraycopy(right, 0, combined, left.length, right.length);
        return combined;
    }
}
