package site.pplee.jcode.codingagent.context;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable result of one successfully applied project instruction discovery. */
public record ProjectContextSnapshot(
        long revision,
        Path workingDirectory,
        List<ProjectContextFile> files,
        List<ProjectContextDiagnostic> diagnostics
) {
    public ProjectContextSnapshot {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        files = List.copyOf(Objects.requireNonNull(files, "files must not be null"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }

    /** Return the disabled, empty snapshot used by compatibility configurations. */
    public static ProjectContextSnapshot disabled(Path workingDirectory) {
        return new ProjectContextSnapshot(0, workingDirectory, List.of(), List.of());
    }

    @Override
    public String toString() {
        return "ProjectContextSnapshot[revision=" + revision + ", workingDirectory=redacted, files="
                + files.size() + ", diagnostics=" + diagnostics.size() + ']';
    }
}
