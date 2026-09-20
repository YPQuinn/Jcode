package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LsToolTest {
    @TempDir
    Path directory;

    @Test
    void preparesDefaultsAndStrictlyValidatesArguments() {
        var tool = new LsTool(directory);
        var factory = JsonNodeFactory.instance;

        var prepared = tool.prepareArguments(factory.objectNode());
        assertEquals(".", prepared.get("path").textValue());
        assertEquals(1_000, prepared.get("limit").intValue());

        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode().putNull("path")));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode().put("limit", 1.5)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode().put("limit", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode().put("limit", 2_001)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.prepareArguments(factory.objectNode().put("unknown", true)));
    }

    @Test
    void listsSpecialNamesWithExplicitTypesAndCodePointOrdering() throws Exception {
        Files.createDirectory(directory.resolve("z-directory"));
        Files.writeString(directory.resolve(".hidden"), "");
        Files.writeString(directory.resolve("a\nb"), "");
        Files.writeString(directory.resolve("quote\"name"), "");
        Files.writeString(directory.resolve("\ue000"), "");
        Files.writeString(directory.resolve("\ud800\udc00"), "");
        Path link = directory.resolve("link");
        try {
            Files.createSymbolicLink(link, Path.of(".hidden"));
        } catch (UnsupportedOperationException | IOException e) {
            link = null;
        }

        String output = execute(new LsTool(directory), new LsToolArguments(".", 100));
        String[] lines = output.split("\\R");

        assertEquals("directory \"z-directory\"", lines[0]);
        assertTrue(output.contains("file \".hidden\""));
        assertTrue(output.contains("file \"a\\nb\""));
        assertTrue(output.contains("file \"quote\\\"name\""));
        assertTrue(output.indexOf("file \"\ue000\"") < output.indexOf("file \"\ud800\udc00\""),
                "supplementary characters must sort by code point rather than UTF-16 units");
        if (link != null) {
            assertTrue(output.contains("symlink \"link\""));
        }
        assertFalse(output.contains(".hidden/"));
    }

    @Test
    void returnsStableGlobalPrefixWhenOnlyResultLimitIsReached() throws Exception {
        Files.writeString(directory.resolve("z"), "");
        Files.writeString(directory.resolve("a"), "");
        Files.writeString(directory.resolve("m"), "");

        String output = execute(new LsTool(directory), new LsToolArguments(".", 2));

        assertTrue(output.startsWith("file \"a\"\nfile \"m\""));
        assertTrue(output.contains("[Result limit reached at 2 entries"));
        assertFalse(output.contains("Directory scan limit reached"));
    }

    @Test
    void distinguishesIncompleteScanFromResultTruncation() throws Exception {
        for (String name : new String[]{"d", "c", "b", "a"}) {
            Files.writeString(directory.resolve(name), "");
        }
        var tool = new LsTool(directory, 2, Duration.ofSeconds(30), System::nanoTime);

        String output = execute(tool, new LsToolArguments(".", 2));
        String body = output.substring(0, output.indexOf("\n\n"));
        String[] records = body.split("\n");

        assertEquals(2, records.length);
        var sorted = records.clone();
        Arrays.sort(sorted);
        assertArrayEquals(sorted, records);
        assertTrue(output.contains("[Directory scan limit reached after 2 entries"));
        assertFalse(output.contains("[Result limit reached"));
    }

    @Test
    void reportsTimeoutWithoutScanningPastTheDeadline() throws Exception {
        Files.writeString(directory.resolve("entry"), "");
        var time = new AtomicLong();
        var tool = new LsTool(
                directory,
                100,
                Duration.ofSeconds(30),
                () -> time.getAndAdd(Duration.ofSeconds(31).toNanos()));

        String output = execute(tool, new LsToolArguments(".", 10));

        assertTrue(output.startsWith("(no entries collected before the directory scan stopped)"));
        assertTrue(output.contains("[Directory scan timeout reached"));
    }

    @Test
    void preservesSymlinkThenParentTraversalSemantics() throws Exception {
        Path real = Files.createDirectories(directory.resolve("real"));
        Files.createDirectory(real.resolve("target"));
        Path expected = Files.createDirectory(real.resolve("sibling"));
        Files.writeString(expected.resolve("native-resolution"), "");
        Path lexical = Files.createDirectory(directory.resolve("sibling"));
        Files.writeString(lexical.resolve("wrong-resolution"), "");
        try {
            Files.createSymbolicLink(directory.resolve("link"), Path.of("real/target"));
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }

        String output = execute(
                new LsTool(directory), new LsToolArguments("link/../sibling", 10));

        assertTrue(output.contains("native-resolution"));
        assertFalse(output.contains("wrong-resolution"));
    }

    @Test
    void outputBudgetNeverReturnsAPartialEntryRecord() throws Exception {
        for (int index = 0; index < 300; index++) {
            String name = "%03d-%s".formatted(index, "x".repeat(196));
            Files.writeString(directory.resolve(name), "");
        }

        String output = execute(new LsTool(directory), new LsToolArguments(".", 1_000));
        String body = output.substring(0, output.indexOf("\n\n"));

        assertTrue(output.contains("entries omitted because a complete record did not fit"));
        assertTrue(output.getBytes(StandardCharsets.UTF_8).length
                <= LsTool.MAX_OUTPUT_BYTES + 2 + 1_024);
        for (String record : body.split("\n")) {
            assertTrue(record.startsWith("file \""));
            assertTrue(record.endsWith("\""));
        }
    }

    @Test
    void emptyMissingNonDirectoryAndCancellationAreDistinctAndRedacted() throws Exception {
        var tool = new LsTool(directory);
        assertEquals("(empty directory)", execute(tool, new LsToolArguments(".", 10)));

        var missing = tool.execute(
                        "call", new LsToolArguments("private/missing", 10),
                        ToolUpdateSink.noop(), new MutableCancellationSignal())
                .toCompletableFuture().join();
        assertTrue(missing.error());
        assertEquals("ls failed: path does not exist", ToolTestSupport.text(missing));
        assertFalse(ToolTestSupport.text(missing).contains("private"));

        Files.writeString(directory.resolve("file"), "");
        var notDirectory = tool.execute(
                        "call", new LsToolArguments("file", 10),
                        ToolUpdateSink.noop(), new MutableCancellationSignal())
                .toCompletableFuture().join();
        assertEquals("ls failed: path is not a directory", ToolTestSupport.text(notDirectory));

        var cancellation = new MutableCancellationSignal();
        cancellation.cancel();
        var cancelled = tool.execute(
                        "call", new LsToolArguments(".", 10),
                        ToolUpdateSink.noop(), cancellation)
                .toCompletableFuture().join();
        assertEquals("ls cancelled", ToolTestSupport.text(cancelled));
    }

    private static String execute(LsTool tool, LsToolArguments arguments) {
        var result = tool.execute(
                        "call", arguments, ToolUpdateSink.noop(), new MutableCancellationSignal())
                .toCompletableFuture().join();
        assertFalse(result.error(), () -> ToolTestSupport.text(result));
        return ToolTestSupport.text(result);
    }
}
