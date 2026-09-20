package site.pplee.jcode.codingagent.context;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Internal bounded loader for project instruction files. */
public final class ProjectContextLoader {
    static final int MAX_FILE_BYTES = 64 * 1024;
    static final int MAX_TOTAL_BYTES = 256 * 1024;
    static final int MAX_FILES = 64;
    static final int MAX_ANCESTORS = 128;
    private static final int MAX_READ_BYTES = 1024 * 1024;
    private static final int MAX_PATH_BYTES = 4 * 1024;
    private static final int MAX_GIT_FILE_BYTES = 8 * 1024;
    private static final int MAX_GIT_READS = 32;
    private static final int MAX_DIAGNOSTICS = 512;
    private static final long DEADLINE_NANOS = Duration.ofSeconds(5).toNanos();
    private static final List<String> CANDIDATES = List.of("AGENTS.override.md", "AGENTS.md", "AGENTS.MD");

    private ProjectContextLoader() {
    }

    /** Load one complete candidate snapshot with the requested revision. */
    public static ProjectContextSnapshot load(
            Path workingDirectory,
            ProjectContextConfig config,
            long revision,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Objects.requireNonNull(config, "config must not be null");
        return load(workingDirectory, config, revision, cancellation, System::nanoTime);
    }

    static ProjectContextSnapshot load(
            Path workingDirectory,
            ProjectContextConfig config,
            long revision,
            CancellationSignal cancellation,
            LongSupplier nanoTime
    ) {
        return load(workingDirectory, config, revision, cancellation, nanoTime, Files::newInputStream);
    }

    /** Package-local I/O seam; all opened streams retain the same budgets and ownership. */
    static ProjectContextSnapshot load(
            Path workingDirectory,
            ProjectContextConfig config,
            long revision,
            CancellationSignal cancellation,
            LongSupplier nanoTime,
            InputOpener inputOpener
    ) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        Objects.requireNonNull(nanoTime, "nanoTime must not be null");
        Objects.requireNonNull(inputOpener, "inputOpener must not be null");
        if (!config.enabled()) {
            return ProjectContextSnapshot.disabled(workingDirectory);
        }
        return new LoadOperation(config, cancellation, nanoTime, inputOpener).load(workingDirectory, revision);
    }

    /** Opens an input stream whose lifetime is owned by one bounded read. */
    @FunctionalInterface
    interface InputOpener {
        InputStream open(Path path) throws IOException;
    }

    private static final class LoadOperation {
        private final ProjectContextConfig config;
        private final CancellationSignal cancellation;
        private final LongSupplier nanoTime;
        private final InputOpener inputOpener;
        private final long deadline;
        private final List<ProjectContextDiagnostic> diagnostics = new ArrayList<>();
        private int bytesRead;
        private int gitReads;

        private LoadOperation(
                ProjectContextConfig config,
                CancellationSignal cancellation,
                LongSupplier nanoTime,
                InputOpener inputOpener
        ) {
            this.config = config;
            this.cancellation = cancellation;
            this.nanoTime = nanoTime;
            this.inputOpener = inputOpener;
            this.deadline = nanoTime.getAsLong() + DEADLINE_NANOS;
        }

        private ProjectContextSnapshot load(Path workingDirectory, long revision) {
            checkpoint();
            Path physicalWorkingDirectory = realDirectory(
                    workingDirectory, ProjectContextDiagnostic.Code.INVALID_DISCOVERY_ROOT);
            Path root = config.discoveryRoot() == null
                    ? physicalWorkingDirectory.getRoot()
                    : realDirectory(config.discoveryRoot(), ProjectContextDiagnostic.Code.INVALID_DISCOVERY_ROOT);
            if (root == null || !physicalWorkingDirectory.startsWith(root)) {
                throw fatal(ProjectContextDiagnostic.Code.DISCOVERY_ROOT_NOT_ANCESTOR,
                        root, physicalWorkingDirectory);
            }

            var directories = new ArrayList<DirectorySource>();
            if (config.globalDirectory() != null) {
                directories.add(new DirectorySource(realDirectory(config.globalDirectory(),
                        ProjectContextDiagnostic.Code.INVALID_GLOBAL_DIRECTORY), ProjectContextScope.GLOBAL));
            }
            var projectDirectories = collectProjectDirectories(physicalWorkingDirectory, root);
            projectDirectories.forEach(path -> directories.add(new DirectorySource(path, ProjectContextScope.PROJECT)));

            var selected = new ArrayList<SelectedSource>();
            for (var directory : directories) {
                checkpoint();
                var candidate = selectCandidate(directory);
                if (candidate != null) {
                    selected.add(candidate);
                }
            }
            applyWorktreeShadow(selected, physicalWorkingDirectory, root);
            var unique = deduplicate(selected);
            var files = new ArrayList<ProjectContextFile>();
            int retainedBytes = 0;
            for (var source : unique) {
                checkpoint();
                try {
                    BasicFileAttributes before = attributes(source.path());
                    if (!stable(source.selectedAttributes(), before)) {
                        sourceFailure(ProjectContextDiagnostic.Code.SOURCE_CHANGED, source.path(), null);
                        continue;
                    }
                    byte[] bytes = readFile(source.path(), MAX_FILE_BYTES, false);
                    BasicFileAttributes after = attributes(source.path());
                    Path physicalAfter = source.path().toRealPath();
                    if (!stable(before, after) || !source.physicalPath().equals(physicalAfter)) {
                        sourceFailure(ProjectContextDiagnostic.Code.SOURCE_CHANGED, source.path(), null);
                        continue;
                    }
                    String content = decode(bytes);
                    if (!isSafeXmlText(content) || !isSafeXmlText(source.path().toString())) {
                        sourceFailure(ProjectContextDiagnostic.Code.UNSAFE_CONTENT, source.path(), null);
                        continue;
                    }
                    // Retention budgets count accepted sources; readFile also charges rejected input.
                    if (files.size() >= MAX_FILES || retainedBytes > MAX_TOTAL_BYTES - bytes.length) {
                        throw fatal(ProjectContextDiagnostic.Code.LOAD_LIMIT_EXCEEDED, source.path(), null);
                    }
                    retainedBytes += bytes.length;
                    files.add(new ProjectContextFile(source.scope(), source.path(), source.physicalPath(),
                            stripBom(content), bytes.length));
                } catch (FileTooLargeException e) {
                    sourceFailure(ProjectContextDiagnostic.Code.SOURCE_TOO_LARGE, source.path(), null);
                } catch (CharacterCodingException e) {
                    sourceFailure(ProjectContextDiagnostic.Code.INVALID_UTF8, source.path(), null);
                } catch (IOException | SecurityException e) {
                    sourceFailure(ProjectContextDiagnostic.Code.SOURCE_UNREADABLE, source.path(), null);
                }
            }
            return new ProjectContextSnapshot(revision, physicalWorkingDirectory, files, diagnostics);
        }

        private List<Path> collectProjectDirectories(Path workingDirectory, Path root) {
            var result = new ArrayList<Path>();
            for (Path current = workingDirectory; current != null; current = current.getParent()) {
                checkpoint();
                result.add(current);
                if (result.size() > MAX_ANCESTORS) {
                    throw fatal(ProjectContextDiagnostic.Code.LOAD_LIMIT_EXCEEDED,
                            workingDirectory, root);
                }
                if (current.equals(root)) {
                    break;
                }
            }
            if (result.isEmpty() || !result.getLast().equals(root)) {
                throw fatal(ProjectContextDiagnostic.Code.DISCOVERY_ROOT_NOT_ANCESTOR, root, workingDirectory);
            }
            Collections.reverse(result);
            return result;
        }

        private SelectedSource selectCandidate(DirectorySource directory) {
            for (String name : CANDIDATES) {
                checkpoint();
                Path candidate = directory.path().resolve(name);
                if (!validPath(candidate)) {
                    sourceFailure(ProjectContextDiagnostic.Code.PATH_TOO_LONG, null, null);
                    return null;
                }
                BasicFileAttributes attributes;
                try {
                    attributes = attributes(candidate);
                } catch (NoSuchFileException e) {
                    try {
                        var linkAttributes = Files.readAttributes(candidate, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                        if (linkAttributes.isSymbolicLink()) {
                            sourceFailure(ProjectContextDiagnostic.Code.SOURCE_IDENTITY_FAILED, candidate, null);
                            return null;
                        }
                    } catch (NoSuchFileException absent) {
                        continue;
                    } catch (IOException | SecurityException identityFailure) {
                        sourceFailure(ProjectContextDiagnostic.Code.SOURCE_IDENTITY_FAILED, candidate, null);
                        return null;
                    }
                    continue;
                } catch (IOException | SecurityException e) {
                    sourceFailure(ProjectContextDiagnostic.Code.SOURCE_IDENTITY_FAILED, candidate, null);
                    return null;
                }
                if (!attributes.isRegularFile()) {
                    addDiagnostic(ProjectContextDiagnostic.Code.NOT_REGULAR_FILE,
                            ProjectContextDiagnostic.Severity.WARNING, candidate, null);
                    continue;
                }
                try {
                    return new SelectedSource(directory.scope(), directory.path(), candidate.toAbsolutePath(),
                            candidate.toRealPath(), attributes);
                } catch (IOException | SecurityException e) {
                    sourceFailure(ProjectContextDiagnostic.Code.SOURCE_IDENTITY_FAILED, candidate, null);
                    return null;
                }
            }
            return null;
        }

        private List<SelectedSource> deduplicate(List<SelectedSource> selected) {
            var unique = new ArrayList<SelectedSource>();
            sourceLoop:
            for (var source : selected) {
                checkpoint();
                for (var existing : unique) {
                    try {
                        if (source.physicalPath().equals(existing.physicalPath())
                                || Files.isSameFile(source.path(), existing.path())) {
                            addDiagnostic(ProjectContextDiagnostic.Code.DUPLICATE_SOURCE,
                                    ProjectContextDiagnostic.Severity.WARNING, source.path(), existing.path());
                            continue sourceLoop;
                        }
                    } catch (IOException | SecurityException e) {
                        sourceFailure(ProjectContextDiagnostic.Code.SOURCE_IDENTITY_FAILED,
                                source.path(), existing.path());
                        continue sourceLoop;
                    }
                }
                unique.add(source);
            }
            return unique;
        }

        private void applyWorktreeShadow(List<SelectedSource> selected, Path cwd, Path root) {
            WorktreeRelation relation;
            try {
                relation = findWorktreeRelation(cwd, root);
            } catch (IOException | InvalidPathException | SecurityException e) {
                gitFailure(cwd);
                return;
            }
            if (relation == null) {
                return;
            }
            SelectedSource worktreeSource = selected.stream()
                    .filter(source -> source.scope() == ProjectContextScope.PROJECT
                            && source.directory().equals(relation.worktreeRoot()))
                    .findFirst().orElse(null);
            if (worktreeSource == null) {
                return;
            }
            for (int index = 0; index < selected.size(); index++) {
                var source = selected.get(index);
                if (source.scope() == ProjectContextScope.PROJECT
                        && source.directory().equals(relation.mainRoot())) {
                    selected.remove(index);
                    addDiagnostic(ProjectContextDiagnostic.Code.SHADOWED_WORKTREE_SOURCE,
                            ProjectContextDiagnostic.Severity.WARNING, source.path(), worktreeSource.path());
                    return;
                }
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
                    attributes = attributes(candidate);
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
            String gitFile = readGitText(dotGit);
            if (!gitFile.startsWith("gitdir: ")) {
                throw new IOException("invalid git file");
            }
            Path gitDir = resolveMetadataPath(dotGit.getParent(), singleLine(gitFile.substring(8))).toRealPath();
            Path commonFile = gitDir.resolve("commondir");
            BasicFileAttributes commonAttributes;
            try {
                checkpoint();
                commonAttributes = Files.readAttributes(commonFile, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missingOptionalMetadata) {
                return null;
            }
            if (commonAttributes.isSymbolicLink()) {
                commonAttributes = attributes(commonFile);
            }
            if (!commonAttributes.isRegularFile()) {
                throw new IOException("invalid worktree common directory metadata");
            }
            Path commonDir = resolveMetadataPath(gitDir, singleLine(readGitText(commonFile))).toRealPath();
            Path backPointer = gitDir.resolve("gitdir");
            if (!Files.isRegularFile(backPointer)) {
                throw new IOException("missing worktree back pointer");
            }
            Path back = resolveMetadataPath(gitDir, singleLine(readGitText(backPointer))).toRealPath();
            if (!Files.isSameFile(back, dotGit)) {
                throw new IOException("worktree back pointer mismatch");
            }
            Path mainRoot = commonDir.getParent();
            if (mainRoot == null || worktreeRoot.equals(mainRoot) || !worktreeRoot.startsWith(mainRoot)
                    || !mainRoot.startsWith(root) || !cwd.startsWith(worktreeRoot)) {
                return null;
            }
            Path mainDotGit = mainRoot.resolve(".git");
            if (!Files.isDirectory(mainDotGit) || !Files.isSameFile(mainDotGit, commonDir)) {
                return null;
            }
            return new WorktreeRelation(mainRoot, worktreeRoot);
        }

        private String readGitText(Path path) throws IOException {
            if (++gitReads > MAX_GIT_READS) {
                throw fatal(ProjectContextDiagnostic.Code.LOAD_LIMIT_EXCEEDED, path, null);
            }
            try {
                return decode(readFile(path, MAX_GIT_FILE_BYTES, true));
            } catch (CharacterCodingException | FileTooLargeException e) {
                throw new IOException("invalid git metadata", e);
            }
        }

        private Path resolveMetadataPath(Path base, String value) throws IOException {
            if (value.isEmpty() || value.indexOf('\0') >= 0) {
                throw new IOException("invalid git metadata path");
            }
            Path parsed = Path.of(value);
            return parsed.isAbsolute() ? parsed : base.resolve(parsed);
        }

        private String singleLine(String value) throws IOException {
            String stripped = value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
            if (stripped.endsWith("\r")) {
                stripped = stripped.substring(0, stripped.length() - 1);
            }
            if (stripped.indexOf('\n') >= 0 || stripped.indexOf('\r') >= 0) {
                throw new IOException("unexpected git metadata lines");
            }
            return stripped;
        }

        private byte[] readFile(Path path, int limit, boolean metadata)
                throws IOException, FileTooLargeException {
            checkpoint();
            try (var input = inputOpener.open(path); var output = new ByteArrayOutputStream()) {
                var buffer = new byte[8192];
                int fileBytes = 0;
                while (true) {
                    checkpoint();
                    int globalRemaining = MAX_READ_BYTES - bytesRead;
                    int count = input.read(buffer, 0,
                            Math.min(buffer.length, Math.min(limit + 1 - fileBytes, globalRemaining + 1)));
                    if (count < 0) {
                        return output.toByteArray();
                    }
                    bytesRead += count;
                    if (bytesRead > MAX_READ_BYTES) {
                        throw fatal(ProjectContextDiagnostic.Code.LOAD_LIMIT_EXCEEDED, path, null);
                    }
                    fileBytes += count;
                    if (fileBytes > limit) {
                        throw new FileTooLargeException();
                    }
                    output.write(buffer, 0, count);
                    if (metadata && fileBytes == limit) {
                        // The next iteration performs one bounded over-limit probe.
                    }
                }
            }
        }

        private BasicFileAttributes attributes(Path path) throws IOException {
            checkpoint();
            return Files.readAttributes(path, BasicFileAttributes.class);
        }

        private Path realDirectory(Path path, ProjectContextDiagnostic.Code code) {
            try {
                Path real = path.toRealPath();
                if (!Files.isDirectory(real)) {
                    throw new IOException("not a directory");
                }
                return real;
            } catch (IOException | SecurityException e) {
                throw fatal(code, path, null);
            }
        }

        private void sourceFailure(ProjectContextDiagnostic.Code code, Path source, Path related) {
            if (config.failureMode() == ProjectContextFailureMode.FAIL) {
                throw fatal(code, source, related);
            }
            addDiagnostic(code, ProjectContextDiagnostic.Severity.WARNING, source, related);
        }

        private void gitFailure(Path source) {
            sourceFailure(ProjectContextDiagnostic.Code.GIT_METADATA_INVALID, source, null);
        }

        private void addDiagnostic(
                ProjectContextDiagnostic.Code code,
                ProjectContextDiagnostic.Severity severity,
                Path source,
                Path related
        ) {
            if (diagnostics.size() >= MAX_DIAGNOSTICS) {
                return;
            }
            diagnostics.add(new ProjectContextDiagnostic(
                    code, severity, boundedPath(source), boundedPath(related)));
        }

        private ProjectContextLoadException fatal(
                ProjectContextDiagnostic.Code code,
                Path source,
                Path related
        ) {
            var diagnostic = new ProjectContextDiagnostic(
                    code, ProjectContextDiagnostic.Severity.ERROR,
                    boundedPath(source), boundedPath(related));
            if (diagnostics.size() >= MAX_DIAGNOSTICS) {
                diagnostics.set(MAX_DIAGNOSTICS - 1, diagnostic);
            } else {
                diagnostics.add(diagnostic);
            }
            return new ProjectContextLoadException(diagnostics);
        }

        private void checkpoint() {
            cancellation.throwIfCancelled();
            if (nanoTime.getAsLong() - deadline >= 0) {
                throw fatal(ProjectContextDiagnostic.Code.LOAD_DEADLINE_EXCEEDED, null, null);
            }
        }

        private Path boundedPath(Path path) {
            return path != null && validPath(path) ? path : null;
        }

        private boolean validPath(Path path) {
            return path.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_PATH_BYTES;
        }
    }

    private static boolean stable(BasicFileAttributes before, BasicFileAttributes after) {
        return before.isRegularFile() && after.isRegularFile()
                && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && (before.fileKey() == null || after.fileKey() == null
                || before.fileKey().equals(after.fileKey()));
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

    private static boolean isSafeXmlText(String value) {
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            boolean valid = codePoint == '\t' || codePoint == '\n' || codePoint == '\r'
                    || codePoint >= 0x20 && codePoint <= 0xd7ff
                    || codePoint >= 0xe000 && codePoint <= 0xfffd
                    || codePoint >= 0x10000 && codePoint <= 0x10ffff;
            if (!valid || (codePoint & 0xffff) == 0xfffe || (codePoint & 0xffff) == 0xffff) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private record DirectorySource(Path path, ProjectContextScope scope) {}
    private record SelectedSource(
            ProjectContextScope scope,
            Path directory,
            Path path,
            Path physicalPath,
            BasicFileAttributes selectedAttributes
    ) {}
    private record WorktreeRelation(Path mainRoot, Path worktreeRoot) {}
    private static final class FileTooLargeException extends Exception {}
}
