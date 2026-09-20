package site.pplee.jcode.codingagent.context;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void ordersGlobalAndConfiguredPathAncestorsAndSelectsHighestPriorityCandidate() throws Exception {
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

        assertEquals(List.of("global", "outer", "override"), contents(snapshot));
        assertEquals(List.of(ProjectContextScope.GLOBAL, ProjectContextScope.PROJECT,
                        ProjectContextScope.PROJECT),
                snapshot.files().stream().map(ProjectContextFile::scope).toList());
        assertFalse(snapshot.files().stream().anyMatch(file -> file.content().contains("must-not-load")));
    }

    @Test
    void fallsBackAfterHigherPriorityCandidateCannotBeRead() throws Exception {
        Files.write(directory.resolve("AGENTS.override.md"), new byte[]{(byte) 0xc3, 0x28});
        Files.writeString(directory.resolve("AGENTS.md"), "fallback");

        var warning = load(directory, new ProjectContextConfig(true, null, directory,
                ProjectContextFailureMode.WARN_AND_SKIP), 1);
        var strict = load(directory, new ProjectContextConfig(true, null, directory,
                ProjectContextFailureMode.FAIL), 2);

        assertEquals(List.of("fallback"), contents(warning));
        assertEquals(List.of("fallback"), contents(strict));
        assertTrue(hasCode(warning, ProjectContextDiagnostic.Code.INVALID_UTF8));
        assertTrue(hasCode(strict, ProjectContextDiagnostic.Code.INVALID_UTF8));
    }

    @Test
    void fallsBackAfterNonRegularOrDanglingOverride() throws Exception {
        Files.createDirectory(directory.resolve("AGENTS.override.md"));
        Files.writeString(directory.resolve("AGENTS.md"), "fallback");
        var nonRegular = load(directory, new ProjectContextConfig(true, null, directory,
                ProjectContextFailureMode.FAIL), 1);
        assertEquals(List.of("fallback"), contents(nonRegular));
        assertTrue(hasCode(nonRegular, ProjectContextDiagnostic.Code.NOT_REGULAR_FILE));

        Files.delete(directory.resolve("AGENTS.override.md"));
        try {
            Files.createSymbolicLink(directory.resolve("AGENTS.override.md"), directory.resolve("missing"));
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("symbolic links are unavailable");
        }
        var dangling = load(directory, new ProjectContextConfig(true, null, directory,
                ProjectContextFailureMode.FAIL), 2);
        assertEquals(List.of("fallback"), contents(dangling));
        assertTrue(hasCode(dangling, ProjectContextDiagnostic.Code.SOURCE_UNREADABLE));
    }

    @Test
    void emptyOrBomOnlyOverrideIsSelectedWithoutFallingBack() throws Exception {
        var child = Files.createDirectory(directory.resolve("child"));
        Files.writeString(directory.resolve("AGENTS.override.md"), "");
        Files.writeString(directory.resolve("AGENTS.md"), "root fallback");
        Files.writeString(child.resolve("AGENTS.override.md"), "\uFEFF");
        Files.writeString(child.resolve("AGENTS.md"), "child fallback");

        var snapshot = load(child, new ProjectContextConfig(true, null, directory,
                ProjectContextFailureMode.FAIL), 1);

        assertEquals(List.of("", ""), contents(snapshot));
        assertEquals(List.of("AGENTS.override.md", "AGENTS.override.md"), snapshot.files().stream()
                .map(ProjectContextFile::discoveredPath)
                .map(Path::getFileName)
                .map(Path::toString)
                .toList());
    }

    @Test
    void strictModeFailsOnlyWhenNoCandidateCanBeLoadedAndRetainsCause() throws Exception {
        Files.write(directory.resolve("AGENTS.override.md"), new byte[]{(byte) 0xc3, 0x28});

        var failure = assertThrows(ProjectContextLoadException.class, () -> load(directory,
                new ProjectContextConfig(true, null, directory, ProjectContextFailureMode.FAIL), 1));

        assertEquals(ProjectContextDiagnostic.Code.INVALID_UTF8, failure.diagnostics().getLast().code());
        assertInstanceOf(CharacterCodingException.class, failure.getCause());
    }

    @Test
    void missingOptionalGlobalDirectoryIsAnEmptySource() throws Exception {
        Files.writeString(directory.resolve("AGENTS.md"), "project rules");
        var missingGlobal = directory.resolve("not-created");

        var snapshot = load(directory, new ProjectContextConfig(true, missingGlobal, directory,
                ProjectContextFailureMode.FAIL), 1);

        assertEquals(List.of("project rules"), contents(snapshot));
        assertTrue(snapshot.diagnostics().isEmpty());
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
    void enforcesOnlyTheAggregateActualReadLimit() throws Exception {
        var child = Files.createDirectory(directory.resolve("child"));
        Files.writeString(directory.resolve("AGENTS.md"), "a".repeat(600 * 1024));
        Files.writeString(child.resolve("AGENTS.md"), "b".repeat(500 * 1024));

        var failure = assertThrows(ProjectContextLoadException.class, () -> load(child,
                new ProjectContextConfig(true, null, directory, ProjectContextFailureMode.WARN_AND_SKIP), 1));

        assertEquals(ProjectContextDiagnostic.Code.LOAD_LIMIT_EXCEEDED,
                failure.diagnostics().getLast().code());
    }

    @Test
    void followsConfiguredPathAncestorsInsteadOfPhysicalAncestors() throws Exception {
        var physical = Files.createDirectories(directory.resolve("physical/repository/child"));
        var logical = Files.createDirectory(directory.resolve("logical"));
        try {
            Files.createSymbolicLink(logical.resolve("project"), physical.getParent());
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("symbolic links are unavailable");
        }
        Files.writeString(logical.resolve("AGENTS.md"), "logical rules");
        Files.writeString(directory.resolve("physical/AGENTS.md"), "physical rules");

        var snapshot = load(logical.resolve("project/child"),
                new ProjectContextConfig(true, null, logical, ProjectContextFailureMode.FAIL), 1);

        assertEquals(List.of("logical rules"), contents(snapshot));
    }

    @Test
    void linkedWorktreeShadowsOnlyTheSameCandidateNameAfterSuccessfulLoad() throws Exception {
        Worktree sameName = createNestedWorktree("AGENTS.md", "AGENTS.md");
        var shadowed = load(sameName.root(), new ProjectContextConfig(true, null, sameName.main(),
                ProjectContextFailureMode.FAIL), 1);
        assertEquals(List.of("worktree rules"), contents(shadowed));
        assertTrue(hasCode(shadowed, ProjectContextDiagnostic.Code.SHADOWED_WORKTREE_SOURCE));

        var secondRoot = Files.createDirectory(directory.resolve("second"));
        Worktree differentNames = createNestedWorktree(secondRoot, "AGENTS.md", "AGENTS.override.md");
        var inherited = load(differentNames.root(), new ProjectContextConfig(true, null, differentNames.main(),
                ProjectContextFailureMode.FAIL), 2);
        assertEquals(List.of("main rules", "worktree rules"), contents(inherited));
        assertFalse(hasCode(inherited, ProjectContextDiagnostic.Code.SHADOWED_WORKTREE_SOURCE));
    }

    @Test
    void failedWorktreeRootSourceDoesNotShadowMainSourceInWarningMode() throws Exception {
        Worktree worktree = createNestedWorktree("AGENTS.md", "AGENTS.md");
        Files.write(worktree.root().resolve("AGENTS.md"), new byte[]{(byte) 0xc3, 0x28});

        var snapshot = load(worktree.root(), new ProjectContextConfig(true, null, worktree.main(),
                ProjectContextFailureMode.WARN_AND_SKIP), 1);

        assertEquals(List.of("main rules"), contents(snapshot));
        assertTrue(hasCode(snapshot, ProjectContextDiagnostic.Code.INVALID_UTF8));
        assertFalse(hasCode(snapshot, ProjectContextDiagnostic.Code.SHADOWED_WORKTREE_SOURCE));
    }

    @Test
    void ordinaryNestedRepositoryKeepsOuterAndInnerSources() throws Exception {
        var nested = Files.createDirectory(directory.resolve("nested"));
        Files.createDirectory(nested.resolve(".git"));
        Files.writeString(directory.resolve("AGENTS.md"), "outer rules");
        Files.writeString(nested.resolve("AGENTS.md"), "inner rules");

        var snapshot = load(nested, new ProjectContextConfig(true, null, directory,
                ProjectContextFailureMode.FAIL), 1);

        assertEquals(List.of("outer rules", "inner rules"), contents(snapshot));
        assertFalse(hasCode(snapshot, ProjectContextDiagnostic.Code.SHADOWED_WORKTREE_SOURCE));
        assertFalse(hasCode(snapshot, ProjectContextDiagnostic.Code.GIT_METADATA_INVALID));
    }

    @Test
    void malformedWorktreeMetadataNeverBlocksNormalDiscovery() throws Exception {
        var nested = Files.createDirectories(directory.resolve("main/nested"));
        Files.createDirectory(directory.resolve("main/.git"));
        Files.writeString(directory.resolve("main/AGENTS.md"), "main rules");
        Files.writeString(nested.resolve("AGENTS.md"), "nested rules");
        Files.writeString(nested.resolve(".git"), "not git metadata");

        var snapshot = load(nested, new ProjectContextConfig(true, null, directory.resolve("main"),
                ProjectContextFailureMode.FAIL), 1);

        assertEquals(List.of("main rules", "nested rules"), contents(snapshot));
        assertTrue(hasCode(snapshot, ProjectContextDiagnostic.Code.GIT_METADATA_INVALID));
    }

    @Test
    void gitMetadataCannotTurnAnAcceptedSourceAtTheReadLimitIntoFailure() throws Exception {
        String instructions = "a".repeat(ProjectContextLoader.MAX_READ_BYTES);
        Files.writeString(directory.resolve("AGENTS.md"), instructions);
        Files.writeString(directory.resolve(".git"), "malformed metadata");

        var snapshot = load(directory, new ProjectContextConfig(true, null, directory,
                ProjectContextFailureMode.FAIL), 1);

        assertEquals(List.of(instructions), contents(snapshot));
        assertTrue(hasCode(snapshot, ProjectContextDiagnostic.Code.GIT_METADATA_INVALID));
    }

    @Test
    void rejectsDiscoveryRootOutsideConfiguredWorkingDirectory() throws Exception {
        var cwd = Files.createDirectory(directory.resolve("cwd"));
        var unrelated = Files.createDirectory(directory.resolve("unrelated"));
        var failure = assertThrows(ProjectContextLoadException.class, () -> load(cwd,
                new ProjectContextConfig(true, null, unrelated, ProjectContextFailureMode.WARN_AND_SKIP), 1));
        assertEquals(ProjectContextDiagnostic.Code.DISCOVERY_ROOT_NOT_ANCESTOR,
                failure.diagnostics().getLast().code());
    }

    private Worktree createNestedWorktree(String mainName, String worktreeName) throws Exception {
        return createNestedWorktree(directory, mainName, worktreeName);
    }

    private Worktree createNestedWorktree(Path parent, String mainName, String worktreeName) throws Exception {
        var main = Files.createDirectory(parent.resolve("main"));
        var gitDirectory = Files.createDirectories(main.resolve(".git/worktrees/nested"));
        var nested = Files.createDirectory(main.resolve("nested"));
        Files.writeString(main.resolve(mainName), "main rules");
        Files.writeString(nested.resolve(worktreeName), "worktree rules");
        Files.writeString(nested.resolve(".git"), "gitdir: " + gitDirectory + "\n");
        Files.writeString(gitDirectory.resolve("HEAD"), "ref: refs/heads/topic\n");
        Files.writeString(gitDirectory.resolve("commondir"), "../..\n");
        return new Worktree(main, nested);
    }

    private ProjectContextSnapshot load(Path cwd, ProjectContextConfig config, long revision) {
        return ProjectContextLoader.load(cwd, config, revision, new CancellationSource().signal());
    }

    private List<String> contents(ProjectContextSnapshot snapshot) {
        return snapshot.files().stream().map(ProjectContextFile::content).toList();
    }

    private boolean hasCode(ProjectContextSnapshot snapshot, ProjectContextDiagnostic.Code code) {
        return snapshot.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code() == code);
    }

    private record Worktree(Path main, Path root) {
    }
}
