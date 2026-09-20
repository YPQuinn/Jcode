package site.pplee.jcode.codingagent.context;

import java.util.List;
import java.util.Objects;

/** Failure to produce a complete project instruction snapshot. */
public final class ProjectContextLoadException extends RuntimeException {
    private final List<ProjectContextDiagnostic> diagnostics;

    public ProjectContextLoadException(List<ProjectContextDiagnostic> diagnostics) {
        this(diagnostics, null);
    }

    public ProjectContextLoadException(List<ProjectContextDiagnostic> diagnostics, Throwable cause) {
        super("project context loading failed", cause);
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }

    /** Return structured diagnostics without project instruction contents. */
    public List<ProjectContextDiagnostic> diagnostics() {
        return diagnostics;
    }
}
