package site.pplee.jcode.codingagent.context;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.codingagent.prompt.SystemPromptBuilder;
import site.pplee.jcode.codingagent.support.NativeToolTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProjectContextBoundaryTest {
    @TempDir
    Path directory;

    @Test
    void doesNotOpenSelectedSourceThatBecameDirectory() throws Exception {
        assertChangedSourceIsNotOpened(Files.createDirectory(directory.resolve("replacement")));
    }

    @Test
    void doesNotOpenSelectedSourceWhoseAttributesChanged() throws Exception {
        assertChangedSourceIsNotOpened(Files.writeString(directory.resolve("replacement"),
                "a different regular file after candidate selection"));
    }

    @Test
    void doesNotOpenSelectedSourceThatBecameFifo() throws Exception {
        NativeToolTestSupport.requirePosixProcessSupport();
        Path mkfifo = Path.of("/usr/bin/mkfifo");
        if (NativeToolTestSupport.strictSmokeRequired()) {
            assertTrue(Files.isExecutable(mkfifo), "/usr/bin/mkfifo is required in strict smoke mode");
        }
        Assumptions.assumeTrue(Files.isExecutable(mkfifo), "/usr/bin/mkfifo is required");
        var replacement = directory.resolve("replacement");
        var process = new ProcessBuilder(mkfifo.toString(), replacement.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertChangedSourceIsNotOpened(replacement);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void skippedInvalidUtf8DoesNotExceedRetainedByteBudget() throws Exception {
        Path cwd = directory;
        for (int index = 0; index < 4; index++) {
            Files.writeString(cwd.resolve("AGENTS.md"), "x".repeat(ProjectContextLoader.MAX_FILE_BYTES));
            cwd = Files.createDirectory(cwd.resolve("child"));
        }
        Files.write(cwd.resolve("AGENTS.md"), new byte[]{(byte) 0xff});

        var snapshot = load(cwd, ProjectContextFailureMode.WARN_AND_SKIP);

        assertEquals(4, snapshot.files().size());
        assertEquals(ProjectContextLoader.MAX_TOTAL_BYTES,
                snapshot.files().stream().mapToLong(ProjectContextFile::rawBytes).sum());
        assertCode(snapshot, ProjectContextDiagnostic.Code.INVALID_UTF8);
    }

    @Test
    void skippedInvalidUtf8DoesNotCountAsRetainedSource() throws Exception {
        Path cwd = directory;
        Files.writeString(cwd.resolve("AGENTS.md"), "valid rules");
        for (int index = 0; index < ProjectContextLoader.MAX_FILES; index++) {
            cwd = Files.createDirectory(cwd.resolve("d"));
            Files.write(cwd.resolve("AGENTS.md"), new byte[]{(byte) 0xff});
        }

        var snapshot = load(cwd, ProjectContextFailureMode.WARN_AND_SKIP);

        assertEquals(List.of("valid rules"), contents(snapshot));
        assertEquals(ProjectContextLoader.MAX_FILES, snapshot.diagnostics().size());
        assertTrue(snapshot.diagnostics().stream()
                .allMatch(diagnostic -> diagnostic.code() == ProjectContextDiagnostic.Code.INVALID_UTF8));
    }

    @Test
    void retainedSourceLimitStillRejectsAnAdditionalValidFile() throws Exception {
        Path cwd = directory;
        for (int index = 0; index < ProjectContextLoader.MAX_FILES; index++) {
            Files.writeString(cwd.resolve("AGENTS.md"), "rules");
            cwd = Files.createDirectory(cwd.resolve("d"));
        }
        assertEquals(ProjectContextLoader.MAX_FILES,
                load(cwd, ProjectContextFailureMode.WARN_AND_SKIP).files().size());
        Files.writeString(cwd.resolve("AGENTS.md"), "additional valid rules");
        Path leaf = cwd;

        var failure = assertThrows(ProjectContextLoadException.class,
                () -> load(leaf, ProjectContextFailureMode.WARN_AND_SKIP));

        assertEquals(ProjectContextDiagnostic.Code.LOAD_LIMIT_EXCEEDED, failure.diagnostics().getLast().code());
    }

    @Test
    void skippedInvalidFilesStillConsumeActualReadBudget() throws Exception {
        Path cwd = directory;
        var invalid = new byte[ProjectContextLoader.MAX_FILE_BYTES];
        Arrays.fill(invalid, (byte) 0xff);
        for (int index = 0; index < 17; index++) {
            Files.write(cwd.resolve("AGENTS.md"), invalid);
            cwd = Files.createDirectory(cwd.resolve("d"));
        }
        Path leaf = cwd;

        var failure = assertThrows(ProjectContextLoadException.class,
                () -> load(leaf, ProjectContextFailureMode.WARN_AND_SKIP));

        assertEquals(ProjectContextDiagnostic.Code.LOAD_LIMIT_EXCEEDED, failure.diagnostics().getLast().code());
    }

    @Test
    void unsafeRenderedPathObeysFailureModeBeforeSnapshotPublication() throws Exception {
        NativeToolTestSupport.requirePosixProcessSupport();
        Files.writeString(directory.resolve("AGENTS.md"), "outer rules");
        var cwd = Files.createDirectory(directory.resolve("unsafe-\u0001-name"));
        Files.writeString(cwd.resolve("AGENTS.override.md"), "must skip");
        Files.writeString(cwd.resolve("AGENTS.md"), "must not fallback");

        var warning = load(cwd, ProjectContextFailureMode.WARN_AND_SKIP);

        assertEquals(List.of("outer rules"), contents(warning));
        assertCode(warning, ProjectContextDiagnostic.Code.UNSAFE_CONTENT);
        assertDoesNotThrow(() -> SystemPromptBuilder.build(cwd, List.of(), null, null, warning.files()));
        var failure = assertThrows(ProjectContextLoadException.class,
                () -> load(cwd, ProjectContextFailureMode.FAIL));
        assertEquals(ProjectContextDiagnostic.Code.UNSAFE_CONTENT, failure.diagnostics().getLast().code());
    }

    @Test
    void nonRegularCommondirWarnsWithoutShadowingMainSource() throws Exception {
        var worktree = worktree();
        Files.createDirectory(worktree.gitDirectory().resolve("commondir"));

        var snapshot = load(worktree.root(), ProjectContextFailureMode.WARN_AND_SKIP);

        assertEquals(List.of("main rules", "worktree rules"), contents(snapshot));
        assertCode(snapshot, ProjectContextDiagnostic.Code.GIT_METADATA_INVALID);
    }

    @Test
    void nonRegularCommondirFailsInStrictMode() throws Exception {
        var worktree = worktree();
        Files.createDirectory(worktree.gitDirectory().resolve("commondir"));

        var failure = assertThrows(ProjectContextLoadException.class,
                () -> load(worktree.root(), ProjectContextFailureMode.FAIL));

        assertEquals(ProjectContextDiagnostic.Code.GIT_METADATA_INVALID, failure.diagnostics().getLast().code());
    }

    @Test
    void danglingCommondirIsNotTreatedAsMissingOptionalMetadata() throws Exception {
        NativeToolTestSupport.requirePosixProcessSupport();
        var worktree = worktree();
        Files.createSymbolicLink(worktree.gitDirectory().resolve("commondir"), Path.of("missing"));

        var snapshot = load(worktree.root(), ProjectContextFailureMode.WARN_AND_SKIP);

        assertCode(snapshot, ProjectContextDiagnostic.Code.GIT_METADATA_INVALID);
        assertThrows(ProjectContextLoadException.class, () -> load(worktree.root(), ProjectContextFailureMode.FAIL));
    }

    @Test
    void absentOptionalCommondirRemainsValidForSeparateGitDirectory() throws Exception {
        var worktree = worktree();

        var snapshot = load(worktree.root(), ProjectContextFailureMode.FAIL);

        assertEquals(List.of("main rules", "worktree rules"), contents(snapshot));
        assertTrue(snapshot.diagnostics().isEmpty());
    }

    private void assertChangedSourceIsNotOpened(Path replacement) throws Exception {
        var root = directory.toRealPath();
        var cwd = Files.createDirectory(root.resolve("child"));
        var outer = Files.writeString(root.resolve("AGENTS.md"), "outer rules");
        var changed = Files.writeString(cwd.resolve("AGENTS.md"), "selected rules");
        var snapshot = ProjectContextLoader.load(cwd, config(ProjectContextFailureMode.WARN_AND_SKIP), 1,
                new CancellationSource().signal(), System::nanoTime, path -> {
                    if (path.equals(outer)) {
                        Files.delete(changed);
                        Files.move(replacement, changed);
                    }
                    assertNotEquals(changed, path, "a known changed/non-regular source must not be opened");
                    return Files.newInputStream(path);
                });

        assertEquals(List.of("outer rules"), contents(snapshot));
        assertCode(snapshot, ProjectContextDiagnostic.Code.SOURCE_CHANGED);
    }

    private Worktree worktree() throws Exception {
        var main = Files.createDirectory(directory.resolve("main"));
        var git = Files.createDirectories(main.resolve(".git/worktrees/topic"));
        var root = Files.createDirectory(main.resolve("topic"));
        Files.writeString(main.resolve("AGENTS.md"), "main rules");
        Files.writeString(root.resolve("AGENTS.md"), "worktree rules");
        Files.writeString(root.resolve(".git"), "gitdir: " + git + "\n");
        Files.writeString(git.resolve("gitdir"), root.resolve(".git") + "\n");
        return new Worktree(root, git);
    }

    private ProjectContextSnapshot load(Path cwd, ProjectContextFailureMode mode) {
        return ProjectContextLoader.load(cwd, config(mode), 1, new CancellationSource().signal());
    }

    private ProjectContextConfig config(ProjectContextFailureMode mode) {
        return new ProjectContextConfig(true, null, directory, mode);
    }

    private List<String> contents(ProjectContextSnapshot snapshot) {
        return snapshot.files().stream().map(ProjectContextFile::content).toList();
    }

    private void assertCode(ProjectContextSnapshot snapshot, ProjectContextDiagnostic.Code code) {
        assertTrue(snapshot.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code() == code),
                () -> "missing diagnostic: " + code);
    }

    private record Worktree(Path root, Path gitDirectory) {
    }
}
