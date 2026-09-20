package site.pplee.jcode.codingagent.context;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Discovers and loads explicit project instruction files without performing prompt rendering. */
public final class ProjectContextLoader {
    static final int MAX_READ_BYTES = 1024 * 1024;
    private static final List<String> CANDIDATES = List.of(
            "AGENTS.override.md", "AGENTS.md", "AGENTS.MD");

    private ProjectContextLoader() {
    }

    /** Load one immutable project instruction snapshot. */
    public static ProjectContextSnapshot load(
            Path workingDirectory,
            ProjectContextConfig config,
            long revision,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        if (!config.enabled()) {
            return ProjectContextSnapshot.disabled(workingDirectory);
        }
        return new LoadOperation(config, cancellation).load(workingDirectory, revision);
    }

    private static final class LoadOperation {
        private final ProjectContextConfig config;
        private final CancellationSignal cancellation;
        private final List<ProjectContextDiagnostic> diagnostics = new ArrayList<>();
        private int bytesRead;

        private LoadOperation(ProjectContextConfig config, CancellationSignal cancellation) {
            this.config = config;
            this.cancellation = cancellation;
        }

        private ProjectContextSnapshot load(Path configuredWorkingDirectory, long revision) {
            Path workingDirectory = configuredWorkingDirectory.toAbsolutePath().normalize();
            requireDirectory(workingDirectory, ProjectContextDiagnostic.Code.INVALID_DISCOVERY_ROOT, false);

            Path discoveryRoot = config.discoveryRoot() == null
                    ? workingDirectory.getRoot()
                    : config.discoveryRoot().toAbsolutePath().normalize();
            requireDirectory(discoveryRoot, ProjectContextDiagnostic.Code.INVALID_DISCOVERY_ROOT, false);
            List<Path> ancestors = ancestors(workingDirectory, discoveryRoot);

            var loaded = new ArrayList<ProjectContextFile>();
            Path globalDirectory = optionalGlobalDirectory();
            if (globalDirectory != null) {
                ProjectContextFile global = loadFromDirectory(globalDirectory, ProjectContextScope.GLOBAL);
                if (global != null) {
                    loaded.add(global);
                }
            }
            for (Path directory : ancestors) {
                checkpoint();
                ProjectContextFile project = loadFromDirectory(directory, ProjectContextScope.PROJECT);
                if (project != null) {
                    loaded.add(project);
                }
            }

            applyWorktreeShadow(loaded, workingDirectory, discoveryRoot);
            return new ProjectContextSnapshot(revision, workingDirectory, deduplicate(loaded), diagnostics);
        }

        private Path optionalGlobalDirectory() {
            if (config.globalDirectory() == null) {
                return null;
            }
            Path directory = config.globalDirectory().toAbsolutePath().normalize();
            return requireDirectory(directory, ProjectContextDiagnostic.Code.INVALID_GLOBAL_DIRECTORY, true)
                    ? directory : null;
        }

        private boolean requireDirectory(
                Path path,
                ProjectContextDiagnostic.Code code,
                boolean missingIsEmpty
        ) {
            checkpoint();
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                if (!attributes.isDirectory()) {
                    throw fatal(code, path, null, null);
                }
                return true;
            } catch (NoSuchFileException e) {
                if (missingIsEmpty && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
                throw fatal(code, path, null, e);
            } catch (IOException | SecurityException e) {
                throw fatal(code, path, null, e);
            }
        }

        private List<Path> ancestors(Path workingDirectory, Path root) {
            if (!workingDirectory.startsWith(root)) {
                throw fatal(ProjectContextDiagnostic.Code.DISCOVERY_ROOT_NOT_ANCESTOR,
                        root, workingDirectory, null);
            }
            var result = new ArrayList<Path>();
            for (Path current = workingDirectory; current != null; current = current.getParent()) {
                checkpoint();
                result.add(current);
                if (current.equals(root)) {
                    break;
                }
            }
            if (result.isEmpty() || !result.getLast().equals(root)) {
                throw fatal(ProjectContextDiagnostic.Code.DISCOVERY_ROOT_NOT_ANCESTOR,
                        root, workingDirectory, null);
            }
            Collections.reverse(result);
            return result;
        }

        private ProjectContextFile loadFromDirectory(Path directory, ProjectContextScope scope) {
            CandidateFailure lastFailure = null;
            for (String name : CANDIDATES) {
                checkpoint();
                Path candidate = directory.resolve(name);
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(candidate, BasicFileAttributes.class);
                } catch (NoSuchFileException e) {
                    if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                        lastFailure = candidateFailure(
                                ProjectContextDiagnostic.Code.SOURCE_UNREADABLE, candidate, e);
                    }
                    continue;
                } catch (IOException | SecurityException e) {
                    lastFailure = candidateFailure(
                            ProjectContextDiagnostic.Code.SOURCE_UNREADABLE, candidate, e);
                    continue;
                }
                if (!attributes.isRegularFile()) {
                    lastFailure = candidateFailure(
                            ProjectContextDiagnostic.Code.NOT_REGULAR_FILE, candidate, null);
                    continue;
                }

                try {
                    byte[] bytes = readFile(candidate, true);
                    String content = stripBom(decode(bytes));
                    Path discoveredPath = candidate.toAbsolutePath().normalize();
                    Path physicalPath = candidate.toRealPath();
                    return new ProjectContextFile(scope, discoveredPath, physicalPath, content, bytes.length);
                } catch (CharacterCodingException e) {
                    lastFailure = candidateFailure(ProjectContextDiagnostic.Code.INVALID_UTF8, candidate, e);
                } catch (IOException | SecurityException e) {
                    lastFailure = candidateFailure(ProjectContextDiagnostic.Code.SOURCE_UNREADABLE, candidate, e);
                }
            }
            if (lastFailure != null && config.failureMode() == ProjectContextFailureMode.FAIL) {
                promoteLastDiagnostic(lastFailure);
                throw new ProjectContextLoadException(diagnostics, lastFailure.cause());
            }
            return null;
        }

        private CandidateFailure candidateFailure(
                ProjectContextDiagnostic.Code code,
                Path source,
                Throwable cause
        ) {
            addDiagnostic(code, ProjectContextDiagnostic.Severity.WARNING, source, null);
            return new CandidateFailure(code, source, cause);
        }

        private void promoteLastDiagnostic(CandidateFailure failure) {
            for (int index = diagnostics.size() - 1; index >= 0; index--) {
                ProjectContextDiagnostic diagnostic = diagnostics.get(index);
                if (diagnostic.code() == failure.code() && Objects.equals(diagnostic.source(), failure.source())) {
                    diagnostics.set(index, new ProjectContextDiagnostic(
                            diagnostic.code(), ProjectContextDiagnostic.Severity.ERROR,
                            diagnostic.source(), diagnostic.relatedSource()));
                    return;
                }
            }
        }

        private List<ProjectContextFile> deduplicate(List<ProjectContextFile> loaded) {
            var unique = new ArrayList<ProjectContextFile>();
            sourceLoop:
            for (ProjectContextFile source : loaded) {
                checkpoint();
                for (ProjectContextFile existing : unique) {
                    try {
                        if (source.physicalPath().equals(existing.physicalPath())
                                || Files.isSameFile(source.discoveredPath(), existing.discoveredPath())) {
                            addDiagnostic(ProjectContextDiagnostic.Code.DUPLICATE_SOURCE,
                                    ProjectContextDiagnostic.Severity.WARNING,
                                    source.discoveredPath(), existing.discoveredPath());
                            continue sourceLoop;
                        }
                    } catch (IOException | SecurityException e) {
                        addDiagnostic(ProjectContextDiagnostic.Code.SOURCE_IDENTITY_FAILED,
                                ProjectContextDiagnostic.Severity.WARNING,
                                source.discoveredPath(), existing.discoveredPath());
                    }
                }
                unique.add(source);
            }
            return List.copyOf(unique);
        }

        private void applyWorktreeShadow(List<ProjectContextFile> loaded, Path cwd, Path root) {
            WorktreeRelation relation;
            try {
                relation = findWorktreeRelation(cwd, root);
            } catch (IOException | InvalidPathException | SecurityException e) {
                addDiagnostic(ProjectContextDiagnostic.Code.GIT_METADATA_INVALID,
                        ProjectContextDiagnostic.Severity.WARNING, cwd, null);
                return;
            }
            if (relation == null) {
                return;
            }

            ProjectContextFile worktreeSource = loaded.stream()
                    .filter(file -> file.scope() == ProjectContextScope.PROJECT)
                    .filter(file -> file.discoveredPath().getParent().equals(relation.worktreeRoot()))
                    .findFirst()
                    .orElse(null);
            if (worktreeSource == null) {
                return;
            }

            String selectedName = worktreeSource.discoveredPath().getFileName().toString();
            for (int index = 0; index < loaded.size(); index++) {
                ProjectContextFile source = loaded.get(index);
                if (source.scope() != ProjectContextScope.PROJECT
                        || !source.discoveredPath().getFileName().toString().equals(selectedName)) {
                    continue;
                }
                Path sourceDirectory = source.discoveredPath().getParent();
                try {
                    if (!sourceDirectory.toRealPath().equals(relation.mainRoot())) {
                        continue;
                    }
                } catch (IOException | SecurityException e) {
                    addDiagnostic(ProjectContextDiagnostic.Code.SOURCE_IDENTITY_FAILED,
                            ProjectContextDiagnostic.Severity.WARNING,
                            source.discoveredPath(), worktreeSource.discoveredPath());
                    continue;
                }
                loaded.remove(index);
                addDiagnostic(ProjectContextDiagnostic.Code.SHADOWED_WORKTREE_SOURCE,
                        ProjectContextDiagnostic.Severity.WARNING,
                        source.discoveredPath(), worktreeSource.discoveredPath());
                return;
            }
        }

        private WorktreeRelation findWorktreeRelation(Path cwd, Path root) throws IOException {
            Path worktreeRoot = null;
            Path dotGit = null;
            for (Path current = cwd; current != null; current = current.getParent()) {
                checkpoint();
                Path candidate = current.resolve(".git");
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(candidate, BasicFileAttributes.class);
                } catch (NoSuchFileException e) {
                    if (current.equals(root)) {
                        break;
                    }
                    continue;
                }
                if (attributes.isDirectory()) {
                    return null;
                }
                if (!attributes.isRegularFile()) {
                    return null;
                }
                worktreeRoot = current;
                dotGit = candidate;
                break;
            }
            if (worktreeRoot == null) {
                return null;
            }

            String gitFile = readGitText(dotGit).trim();
            if (!gitFile.startsWith("gitdir: ")) {
                throw new IOException("invalid git file");
            }
            Path gitDirectory = resolveMetadataPath(worktreeRoot, gitFile.substring(8).trim()).toRealPath();
            if (!Files.exists(gitDirectory.resolve("HEAD"))) {
                return null;
            }
            Path commonFile = gitDirectory.resolve("commondir");
            if (!Files.exists(commonFile, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            if (!Files.isRegularFile(commonFile)) {
                throw new IOException("invalid common directory metadata");
            }
            Path commonDirectory = resolveMetadataPath(
                    gitDirectory, readGitText(commonFile).trim()).toRealPath();
            Path mainRoot = commonDirectory.getParent();
            Path physicalWorktreeRoot = worktreeRoot.toRealPath();
            if (mainRoot == null || physicalWorktreeRoot.equals(mainRoot)
                    || !physicalWorktreeRoot.startsWith(mainRoot)) {
                return null;
            }
            Path mainDotGit = mainRoot.resolve(".git");
            if (!Files.isDirectory(mainDotGit) || !mainDotGit.toRealPath().equals(commonDirectory)) {
                return null;
            }
            return new WorktreeRelation(mainRoot, worktreeRoot);
        }

        private String readGitText(Path path) throws IOException {
            try {
                return decode(readFile(path, false));
            } catch (CharacterCodingException e) {
                throw new IOException("invalid UTF-8 Git metadata", e);
            }
        }

        private Path resolveMetadataPath(Path base, String value) throws IOException {
            if (value.isEmpty() || value.indexOf('\0') >= 0) {
                throw new IOException("invalid Git metadata path");
            }
            Path parsed = Path.of(value);
            return parsed.isAbsolute() ? parsed : base.resolve(parsed);
        }

        private byte[] readFile(Path path, boolean failWhenLimitExceeded) throws IOException {
            checkpoint();
            try (var input = Files.newInputStream(path); var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                while (true) {
                    checkpoint();
                    int remaining = MAX_READ_BYTES - bytesRead;
                    int count = input.read(buffer, 0, Math.min(buffer.length, remaining + 1));
                    if (count < 0) {
                        return output.toByteArray();
                    }
                    bytesRead += count;
                    if (bytesRead > MAX_READ_BYTES) {
                        if (failWhenLimitExceeded) {
                            throw fatal(ProjectContextDiagnostic.Code.LOAD_LIMIT_EXCEEDED,
                                    path, null, null);
                        }
                        throw new IOException("project context read limit exceeded");
                    }
                    output.write(buffer, 0, count);
                }
            }
        }

        private void addDiagnostic(
                ProjectContextDiagnostic.Code code,
                ProjectContextDiagnostic.Severity severity,
                Path source,
                Path related
        ) {
            diagnostics.add(new ProjectContextDiagnostic(code, severity, source, related));
        }

        private ProjectContextLoadException fatal(
                ProjectContextDiagnostic.Code code,
                Path source,
                Path related,
                Throwable cause
        ) {
            diagnostics.add(new ProjectContextDiagnostic(
                    code, ProjectContextDiagnostic.Severity.ERROR, source, related));
            return new ProjectContextLoadException(diagnostics, cause);
        }

        private void checkpoint() {
            cancellation.throwIfCancelled();
        }
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private record CandidateFailure(
            ProjectContextDiagnostic.Code code,
            Path source,
            Throwable cause
    ) {
    }

    private record WorktreeRelation(Path mainRoot, Path worktreeRoot) {
    }
}
