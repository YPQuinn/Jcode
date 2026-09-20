package site.pplee.jcode.codingagent.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;
import site.pplee.jcode.codingagent.support.NativeToolTestSupport;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SearchToolsNativeTest {
    @TempDir
    Path directory;

    private RipgrepBackend backend;

    @AfterEach
    void closeBackend() {
        if (backend != null) {
            backend.close();
        }
    }

    @Test
    void realRipgrepHonorsIgnorePostFilterSpecialPathsAndErrorSemantics() throws Exception {
        Path executable = NativeToolTestSupport.requireRipgrep();
        Files.createDirectory(directory.resolve(".git"));
        Files.writeString(directory.resolve(".gitignore"), "ignored.java\n");
        Files.writeString(directory.resolve(".ignore"), "ignored-too.java\n");
        Files.writeString(directory.resolve("kept.java"), "needle\n");
        Files.writeString(directory.resolve("ignored.java"), "needle\n");
        Files.writeString(directory.resolve("ignored-too.java"), "needle\n");
        Files.writeString(directory.resolve(".hidden.java"), "needle\n");
        Files.writeString(directory.resolve("special\nname.txt"), "$HOME [literal]\n");
        try (var file = new RandomAccessFile(directory.resolve("large.java").toFile(), "rw")) {
            file.writeBytes("needle\n");
            file.setLength(SearchToolSupport.MAX_FILE_BYTES + 1L);
        }

        backend = RipgrepBackend.open(directory, new SearchConfig(executable, Map.of()));
        var find = new FindTool(directory, backend);
        var grep = new GrepTool(directory, backend);

        String found = text(executeFind(find, new FindToolArguments("**/*.java", ".", 100)));
        assertTrue(found.contains("\"kept.java\""));
        assertFalse(found.contains("ignored.java"));
        assertFalse(found.contains("ignored-too.java"));
        assertFalse(found.contains(".hidden.java"));
        assertFalse(found.contains("large.java"));

        String matches = text(executeGrep(grep,
                new GrepToolArguments("needle", ".", "*.java", false, false, 100)));
        assertTrue(matches.contains("\"kept.java\":1: \"needle\""));
        assertFalse(matches.contains("ignored.java"));
        assertFalse(matches.contains("ignored-too.java"));
        assertFalse(matches.contains(".hidden.java"));
        assertFalse(matches.contains("large.java"));

        String explicitlyRequestedIgnoredFile = text(executeGrep(grep,
                new GrepToolArguments(
                        "needle", "ignored.java", null, false, true, 10)));
        assertTrue(explicitlyRequestedIgnoredFile.contains("\"ignored.java\":1"));

        String special = text(executeGrep(grep,
                new GrepToolArguments("$HOME [literal]", ".", "*.txt", false, true, 10)));
        assertTrue(special.contains("\"special\\nname.txt\":1"));

        var invalid = executeGrep(grep,
                new GrepToolArguments("[", ".", null, false, false, 10));
        assertTrue(invalid.error());
        assertTrue(text(invalid).contains("invalid search pattern"));

        var noMatch = executeGrep(grep,
                new GrepToolArguments("absent", ".", null, false, true, 10));
        assertFalse(noMatch.error());
        assertTrue(text(noMatch).startsWith("(no matches)"));
    }

    @Test
    void explicitFilesKeepSizeBoundsAndNeverSelectStdin() throws Exception {
        Path executable = NativeToolTestSupport.requireRipgrep();
        Files.writeString(directory.resolve("-"), "needle\n");
        Files.writeString(directory.resolve("large.txt"),
                "needle\n" + "x".repeat(SearchToolSupport.MAX_FILE_BYTES));
        backend = RipgrepBackend.open(directory, new SearchConfig(executable, Map.of()));
        var grep = new GrepTool(directory, backend);

        var dash = executeGrep(grep,
                new GrepToolArguments("needle", "-", null, false, true, 10));
        assertFalse(dash.error());
        assertTrue(text(dash).contains("\"-\":1: \"needle\""));
        var oversized = executeGrep(grep,
                new GrepToolArguments("needle", "large.txt", null, false, true, 10));
        assertTrue(oversized.error());
        assertTrue(text(oversized).contains("8 MiB"));
        assertFalse(text(oversized).contains("\"large.txt\":1:"));
    }

    @Test
    void globFiltersIncludeFileNamesContainingLiteralStars() throws Exception {
        Path executable = NativeToolTestSupport.requireRipgrep();
        Files.writeString(directory.resolve("*report.txt"), "needle\n");
        backend = RipgrepBackend.open(directory, new SearchConfig(executable, Map.of()));
        var found = executeFind(new FindTool(directory, backend),
                new FindToolArguments("*", ".", 10));
        assertTrue(text(found).contains("\"*report.txt\""));
        var matches = executeGrep(new GrepTool(directory, backend),
                new GrepToolArguments("needle", ".", "*.txt", false, true, 10));
        assertTrue(text(matches).contains("\"*report.txt\":1:"));
    }

    private static ToolExecutionResult executeFind(FindTool tool, FindToolArguments arguments) {
        return tool.execute(
                        "find", arguments, ToolUpdateSink.noop(), new MutableCancellationSignal())
                .toCompletableFuture().join();
    }

    private static ToolExecutionResult executeGrep(GrepTool tool, GrepToolArguments arguments) {
        return tool.execute(
                        "grep", arguments, ToolUpdateSink.noop(), new MutableCancellationSignal())
                .toCompletableFuture().join();
    }

    private static String text(ToolExecutionResult result) {
        return ToolTestSupport.text(result);
    }
}
