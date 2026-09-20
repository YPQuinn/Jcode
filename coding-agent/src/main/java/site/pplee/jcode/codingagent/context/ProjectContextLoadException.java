package site.pplee.jcode.codingagent.context;

import java.util.List;
import java.util.Objects;

/** Failure to produce a complete project instruction snapshot. */
public final class ProjectContextLoadException extends RuntimeException {
    private final List<ProjectContextDiagnostic> diagnostics;

    public ProjectContextLoadException(List<ProjectContextDiagnostic> diagnostics) {
        super("project context loading failed");
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }

    /** Return bounded structured diagnostics without underlying exception text. */
    public List<ProjectContextDiagnostic> diagnostics() {
        return diagnostics;
    }
}
