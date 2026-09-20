package site.pplee.jcode.codingagent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;
import site.pplee.jcode.codingagent.support.NativeToolTestSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalToolAlignmentTest {
    @TempDir
    Path directory;

    @Test
    void overwriteDoesNotReadOrLimitThePreviousFile() throws Exception {
        Files.write(directory.resolve("large.txt"), new byte[8 * 1024 * 1024 + 1]);
        var result = write("large.txt", "small");
        assertFalse(result.error(), () -> ToolTestSupport.text(result));
        assertEquals("small", Files.readString(directory.resolve("large.txt")));
    }

    @Test
    void writeAcceptsContentBeyondTheFormerFileLimit() throws Exception {
        var content = "x".repeat(8 * 1024 * 1024 + 1);
        var result = write("large.txt", content);
        assertFalse(result.error(), () -> ToolTestSupport.text(result));
        assertEquals(content.length(), Files.size(directory.resolve("large.txt")));
    }

    @Test
    void overwriteRetainsHardlinkIdentity() throws Exception {
        NativeToolTestSupport.requirePosixProcessSupport();
        var target = Files.writeString(directory.resolve("target.txt"), "old");
        var alias = Files.createLink(directory.resolve("alias.txt"), target);
        assertFalse(write("target.txt", "new").error());
        assertTrue(Files.isSameFile(target, alias));
        assertEquals("new", Files.readString(alias));
    }

    @Test
    void editNormalizesOnlyTouchedLinesWhenExactMatchingFails() {
        String original = "keep  \r\nhello   \r\n“world”\r\nuntouched　\r\n";
        var plan = new EditPlanner().plan(original.getBytes(StandardCharsets.UTF_8),
                List.of(new EditReplacement("hello\n\"world\"", "updated")));
        assertEquals("keep  \r\nupdated\r\nuntouched　\r\n",
                new String(plan.finalBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void editSupportsCompatibilityCharactersAndPreservesOtherLines() {
        var plan = new EditPlanner().plan("keep ﬀ  \nｆｉｌｅ — name\n".getBytes(StandardCharsets.UTF_8),
                List.of(new EditReplacement("file - name", "changed")));
        assertEquals("keep ﬀ  \nchanged\n", new String(plan.finalBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void shellInitializationVariablesRemainAnExplicitCallerChoice() {
        assertDoesNotThrow(() -> new BashConfig(Path.of("/bin/bash"), Map.of("BASH_ENV", "/dev/null"),
                Duration.ofSeconds(30), Duration.ofHours(2)));
    }

    @Test
    void shellEnvironmentAndTimeoutCanBeInheritedAndOmitted() {
        var config = assertDoesNotThrow(() -> new BashConfig(Path.of("/bin/bash"), null, null, null));
        assertNull(config.environment());
        assertNull(config.defaultTimeout());
        assertNull(config.maximumTimeout());
    }

    @Test
    void filesystemDiagnosticsKeepErrorKindAndPath() {
        assertTrue(FileToolSupport.safeMessage(new NoSuchFileException("missing.txt")).contains("missing.txt"));
        assertTrue(FileToolSupport.safeMessage(new AccessDeniedException("protected.txt")).contains("protected.txt"));
        assertNotEquals(FileToolSupport.safeMessage(new NoSuchFileException("same.txt")),
                FileToolSupport.safeMessage(new AccessDeniedException("same.txt")));
    }

    @Test
    void nativeSearchIncludesHiddenFilesAndDelegatesBraceGlobs() throws Exception {
        Files.createDirectory(directory.resolve(".github"));
        Files.writeString(directory.resolve(".github/ci.yml"), "needle\n");
        var config = new SearchConfig(NativeToolTestSupport.requireRipgrep(), NativeToolTestSupport.requireFd(), Map.of());
        try (var grep = new GrepTool(directory, config); var find = new FindTool(directory, config)) {
            var result = grep(grep, new GrepToolArguments("needle", ".", "*.{yml,yaml}", false, true, 10));
            assertFalse(result.error(), () -> ToolTestSupport.text(result));
            assertTrue(ToolTestSupport.text(result).contains(".github/ci.yml"));
            var found = find.execute("find", new FindToolArguments("*.{yml,yaml}", ".", 10),
                    ToolUpdateSink.noop(), new MutableCancellationSignal()).toCompletableFuture().join();
            assertFalse(found.error(), () -> ToolTestSupport.text(found));
            assertTrue(ToolTestSupport.text(found).contains(".github/ci.yml"));
        }
    }

    @Test
    void explicitNativeGlobCanIncludeIgnoredFiles() throws Exception {
        Files.createDirectory(directory.resolve(".git"));
        Files.writeString(directory.resolve(".gitignore"), "ignored.java\n");
        Files.writeString(directory.resolve("ignored.java"), "needle\n");
        try (var grep = new GrepTool(directory, new SearchConfig(NativeToolTestSupport.requireRipgrep(), NativeToolTestSupport.requireFd(), Map.of()))) {
            var implicit = grep(grep, new GrepToolArguments("needle", ".", null, false, true, 10));
            assertFalse(ToolTestSupport.text(implicit).contains("ignored.java"));
            var explicit = grep(grep, new GrepToolArguments("needle", ".", "ignored.java", false, true, 10));
            assertFalse(explicit.error(), () -> ToolTestSupport.text(explicit));
            assertTrue(ToolTestSupport.text(explicit).contains("ignored.java"));
        }
    }

    private ToolExecutionResult write(String path, String content) {
        return new WriteTool(directory).execute("write", new WriteToolArguments(path, content),
                ToolUpdateSink.noop(), new MutableCancellationSignal()).toCompletableFuture().join();
    }

    private ToolExecutionResult grep(GrepTool tool, GrepToolArguments arguments) {
        return tool.execute("grep", arguments, ToolUpdateSink.noop(), new MutableCancellationSignal())
                .toCompletableFuture().join();
    }
}
