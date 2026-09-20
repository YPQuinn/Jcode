package site.pplee.jcode.codingagent.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectContextLoaderTest {
    @TempDir
    Path directory;

    @Test
    void disabledModePerformsNoDiscoveryIo() {
        var missing = directory.resolve("missing");
        var snapshot = load(missing, ProjectContextConfig.disabled(), 0);

        assertEquals(0, snapshot.revision());
        assertEquals(missing, snapshot.workingDirectory());
        assertTrue(snapshot.files().isEmpty());
    }

    @Test
    void ordersGlobalAndPhysicalAncestorsAndSelectsHighestPriorityCandidate() throws Exception {
        var global = Files.createDirectory(directory.resolve("global"));
        var project = Files.createDirectory(directory.resolve("project"));
        var module = Files.createDirectory(project.resolve("module"));
        Files.writeString(global.resolve("AGENTS.md"), "\uFEFFglobal");
        Files.writeString(directory.resolve("AGENTS.md"), "outer");
        Files.writeString(project.resolve("AGENTS.MD"), "lower");
        Files.writeString(project.resolve("AGENTS.override.md"), "override");
        Files.writeString(module.resolve("CLAUDE.md"), "must-not-load");

        var config = new ProjectContextConfig(true, global, directory,
                ProjectContextFailureMode.WARN_AND_SKIP);
        var snapshot = load(module, config, 1);

        assertEquals(List.of("global", "outer", "override"),
                snapshot.files().stream().map(ProjectContextFile::content).toList());
        assertEquals(List.of(ProjectContextScope.GLOBAL, ProjectContextScope.PROJECT,
                        ProjectContextScope.PROJECT),
                snapshot.files().stream().map(ProjectContextFile::scope).toList());
        assertFalse(snapshot.files().stream().anyMatch(file -> file.content().contains("must-not-load")));
    }

    @Test
    void skipsNonRegularCandidateButDoesNotFallbackAfterSelectedFileFailure() throws Exception {
        Files.createDirectory(directory.resolve("AGENTS.override.md"));
        Files.writeString(directory.resolve("AGENTS.md"), "fallback");
        var warning = load(directory, new ProjectContextConfig(true, null, directory,
                ProjectContextFailureMode.WARN_AND_SKIP), 1);
        assertEquals(List.of("fallback"), warning.files().stream().map(ProjectContextFile::content).toList());
        assertTrue(hasCode(warning, ProjectContextDiagnostic.Code.NOT_REGULAR_FILE));

        Files.delete(directory.resolve("AGENTS.override.md"));
        Files.write(directory.resolve("AGENTS.override.md"), new byte[]{(byte) 0xc3, 0x28});
        var invalid = load(directory, new ProjectContextConfig(true, null, directory,
                ProjectContextFailureMode.WARN_AND_SKIP), 2);
        assertTrue(invalid.files().isEmpty());
        assertTrue(hasCode(invalid, ProjectContextDiagnostic.Code.INVALID_UTF8));

        assertThrows(ProjectContextLoadException.class, () -> load(directory,
                new ProjectContextConfig(true, null, directory, ProjectContextFailureMode.FAIL), 3));
    }

    @Test
    void danglingHighPrioritySymlinkDoesNotFallback() throws Exception {
        try {
            Files.createSymbolicLink(directory.resolve("AGENTS.override.md"), directory.resolve("missing"));
        } catch (UnsupportedOperationException e) {
            return;
        }
        Files.writeString(directory.resolve("AGENTS.md"), "must not load");

        var snapshot = load(directory, new ProjectContextConfig(true, null, directory,
                ProjectContextFailureMode.WARN_AND_SKIP), 1);

        assertTrue(snapshot.files().isEmpty());
        assertTrue(hasCode(snapshot, ProjectContextDiagnostic.Code.SOURCE_IDENTITY_FAILED));
    }

    @Test
    void deduplicatesPhysicalFilesWhileKeepingFirstScope() throws Exception {
        var global = Files.createDirectory(directory.resolve("global"));
        var project = Files.createDirectory(directory.resolve("project"));
        var source = Files.writeString(global.resolve("AGENTS.md"), "shared");
        try {
            Files.createLink(project.resolve("AGENTS.md"), source);
        } catch (UnsupportedOperationException | IOException e) {
            Files.createSymbolicLink(project.resolve("AGENTS.md"), source);
        }

        var snapshot = load(project, new ProjectContextConfig(true, global, project,
                ProjectContextFailureMode.WARN_AND_SKIP), 1);

        assertEquals(1, snapshot.files().size());
        assertEquals(ProjectContextScope.GLOBAL, snapshot.files().getFirst().scope());
        assertTrue(hasCode(snapshot, ProjectContextDiagnostic.Code.DUPLICATE_SOURCE));
    }

    @Test
    void enforcesStrictUtf8AndFileAndAggregateBudgets() throws Exception {
        Files.write(directory.resolve("AGENTS.md"), new byte[ProjectContextLoader.MAX_FILE_BYTES + 1]);
        var warning = load(directory, new ProjectContextConfig(true, null, directory,
                ProjectContextFailureMode.WARN_AND_SKIP), 1);
        assertTrue(warning.files().isEmpty());
        assertTrue(hasCode(warning, ProjectContextDiagnostic.Code.SOURCE_TOO_LARGE));

        var level = directory;
        Files.delete(level.resolve("AGENTS.md"));
        byte[] validText = "a".repeat(60 * 1024).getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < 5; index++) {
            level = Files.createDirectory(level.resolve("d" + index));
            Files.write(level.resolve("AGENTS.md"), validText);
        }
        Path cwd = level;
        var failure = assertThrows(ProjectContextLoadException.class, () -> load(cwd,
                new ProjectContextConfig(true, null, directory, ProjectContextFailureMode.WARN_AND_SKIP), 2));
        assertEquals(ProjectContextDiagnostic.Code.LOAD_LIMIT_EXCEEDED,
                failure.diagnostics().getLast().code());
    }

    @Test
    void reportsCooperativeDeadlineAsStructuredLoadFailure() {
        var calls = new int[1];
        var config = new ProjectContextConfig(true, null, directory, ProjectContextFailureMode.WARN_AND_SKIP);

        var failure = assertThrows(ProjectContextLoadException.class, () -> ProjectContextLoader.load(
                directory, config, 1, new CancellationSource().signal(),
                () -> calls[0]++ == 0 ? 0 : 5_000_000_000L));

        assertEquals(ProjectContextDiagnostic.Code.LOAD_DEADLINE_EXCEEDED,
                failure.diagnostics().getLast().code());
    }

    @Test
    void nestedLinkedWorktreeShadowsOnlyMainRepositoryRoot() throws Exception {
        var main = Files.createDirectory(directory.resolve("main"));
        var commonGit = Files.createDirectories(main.resolve(".git/worktrees/nested"));
        var nested = Files.createDirectory(main.resolve("nested"));
        Files.writeString(main.resolve("AGENTS.md"), "main rules");
        Files.writeString(nested.resolve("AGENTS.override.md"), "worktree rules");
        Files.writeString(nested.resolve(".git"), "gitdir: " + commonGit + "\n");
        Files.writeString(commonGit.resolve("commondir"), "../..\n");
        Files.writeString(commonGit.resolve("gitdir"), nested.resolve(".git") + "\n");

        var snapshot = load(nested, new ProjectContextConfig(true, null, directory,
                ProjectContextFailureMode.FAIL), 1);

        assertEquals(List.of("worktree rules"),
                snapshot.files().stream().map(ProjectContextFile::content).toList());
        assertTrue(hasCode(snapshot, ProjectContextDiagnostic.Code.SHADOWED_WORKTREE_SOURCE));
    }

    @Test
    void ordinaryNestedRepositoryDoesNotShadowAncestorInstructions() throws Exception {
        var outer = Files.createDirectory(directory.resolve("outer"));
        var nested = Files.createDirectory(outer.resolve("nested"));
        Files.createDirectory(nested.resolve(".git"));
        Files.writeString(outer.resolve("AGENTS.md"), "outer rules");
        Files.writeString(nested.resolve("AGENTS.md"), "nested rules");

        var snapshot = load(nested, new ProjectContextConfig(true, null, directory,
                ProjectContextFailureMode.FAIL), 1);

        assertEquals(List.of("outer rules", "nested rules"),
                snapshot.files().stream().map(ProjectContextFile::content).toList());
        assertFalse(hasCode(snapshot, ProjectContextDiagnostic.Code.SHADOWED_WORKTREE_SOURCE));
    }

    @Test
    void rejectsDiscoveryRootOutsidePhysicalWorkingDirectory() throws Exception {
        var cwd = Files.createDirectory(directory.resolve("cwd"));
        var unrelated = Files.createDirectory(directory.resolve("unrelated"));
        var failure = assertThrows(ProjectContextLoadException.class, () -> load(cwd,
                new ProjectContextConfig(true, null, unrelated, ProjectContextFailureMode.WARN_AND_SKIP), 1));
        assertEquals(ProjectContextDiagnostic.Code.DISCOVERY_ROOT_NOT_ANCESTOR,
                failure.diagnostics().getLast().code());
    }

    private ProjectContextSnapshot load(Path cwd, ProjectContextConfig config, long revision) {
        return ProjectContextLoader.load(cwd, config, revision, new CancellationSource().signal());
    }

    private boolean hasCode(ProjectContextSnapshot snapshot, ProjectContextDiagnostic.Code code) {
        return snapshot.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code() == code);
    }
}
