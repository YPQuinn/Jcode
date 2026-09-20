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

    private ConfiguredSearchTools backend;

    @AfterEach
    void closeBackend() {
        if (backend != null) {
            backend.close();
        }
    }

    @Test
    void nativeToolsDelegateGlobHiddenIgnoreAndErrorSemantics() throws Exception {
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
            file.setLength((8 * 1024 * 1024) + 1L);
        }

        backend = ConfiguredSearchTools.open(directory, new SearchConfig(executable, NativeToolTestSupport.requireFd(), Map.of()));
        var find = backend.find();
        var grep = backend.grep();

        String found = text(executeFind(find, new FindToolArguments("**/*.java", ".", 100)));
        assertTrue(found.contains("\"kept.java\""));
        assertFalse(found.contains("ignored.java"));
        assertFalse(found.contains("ignored-too.java"));
        assertTrue(found.contains(".hidden.java"));
        assertTrue(found.contains("large.java"));

        String matches = text(executeGrep(grep,
                new GrepToolArguments("needle", ".", "*.java", false, false, 100)));
        assertTrue(matches.contains("\"kept.java\":1: \"needle\""));
        assertTrue(matches.contains("ignored.java"));
        assertTrue(matches.contains("ignored-too.java"));
        assertTrue(matches.contains(".hidden.java"));
        // The sparse file is binary; rg retains its native binary-file behavior.

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
        assertTrue(text(invalid).contains("regex parse error"));

        var noMatch = executeGrep(grep,
                new GrepToolArguments("absent", ".", null, false, true, 10));
        assertFalse(noMatch.error());
        assertTrue(text(noMatch).startsWith("(no matches)"));
    }

    @Test
    void explicitFilesAreNotSizeLimitedAndNeverSelectStdin() throws Exception {
        Path executable = NativeToolTestSupport.requireRipgrep();
        Files.writeString(directory.resolve("-"), "needle\n");
        Files.writeString(directory.resolve("large.txt"),
                "needle\n" + "x".repeat((8 * 1024 * 1024)));
        backend = ConfiguredSearchTools.open(directory, new SearchConfig(executable, NativeToolTestSupport.requireFd(), Map.of()));
        var grep = backend.grep();

        var dash = executeGrep(grep,
                new GrepToolArguments("needle", "-", null, false, true, 10));
        assertFalse(dash.error());
        assertTrue(text(dash).contains("\"-\":1: \"needle\""));
        var oversized = executeGrep(grep,
                new GrepToolArguments("needle", "large.txt", null, false, true, 10));
        assertFalse(oversized.error(), () -> text(oversized));
        assertTrue(text(oversized).contains("\"large.txt\":1:"));
    }

    @Test
    void globFiltersIncludeFileNamesContainingLiteralStars() throws Exception {
        Path executable = NativeToolTestSupport.requireRipgrep();
        Files.writeString(directory.resolve("*report.txt"), "needle\n");
        backend = ConfiguredSearchTools.open(directory, new SearchConfig(executable, NativeToolTestSupport.requireFd(), Map.of()));
        var found = executeFind(backend.find(),
                new FindToolArguments("*", ".", 10));
        assertTrue(text(found).contains("\"*report.txt\""));
        var matches = executeGrep(backend.grep(),
                new GrepToolArguments("needle", ".", "*.txt", false, true, 10));
        assertTrue(text(matches).contains("\"*report.txt\":1:"));
    }

    @Test
    void fileFinderHonorsIgnoreOutsideRepositoriesAndReportsInvalidGlobs() throws Exception {
        Files.writeString(directory.resolve(".gitignore"), "ignored.txt\n");
        Files.writeString(directory.resolve("ignored.txt"), "ignored");
        Files.writeString(directory.resolve("kept.txt"), "kept");
        backend = ConfiguredSearchTools.open(directory, new SearchConfig(
                NativeToolTestSupport.requireRipgrep(), NativeToolTestSupport.requireFd(), Map.of()));
        var found = executeFind(backend.find(), new FindToolArguments("*.txt", ".", 10));
        assertFalse(found.error(), () -> text(found));
        assertTrue(text(found).contains("kept.txt"));
        assertFalse(text(found).contains("ignored.txt"));
        var invalid = executeFind(backend.find(), new FindToolArguments("[", ".", 10));
        assertTrue(invalid.error(), () -> text(invalid));
        assertTrue(text(invalid).contains("glob"), () -> text(invalid));
    }

    @Test
    void fileFinderStopsParentIgnoreRulesAtNestedRepositoryBoundary() throws Exception {
        Files.createDirectory(directory.resolve(".git"));
        Files.writeString(directory.resolve(".gitignore"), "*.txt\n");
        Path nested = Files.createDirectories(directory.resolve("nested/.git")).getParent();
        Files.writeString(nested.resolve("kept.txt"), "kept");
        backend = ConfiguredSearchTools.open(nested, new SearchConfig(
                NativeToolTestSupport.requireRipgrep(), NativeToolTestSupport.requireFd(), Map.of()));
        var result = executeFind(backend.find(), new FindToolArguments("*.txt", ".", 10));
        assertFalse(result.error(), () -> text(result));
        assertTrue(text(result).contains("kept.txt"));
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
