package site.pplee.jcode.codingagent.context;

import java.nio.file.Path;
import java.util.Objects;

/** Structured diagnostic produced during project instruction discovery. */
public record ProjectContextDiagnostic(
        Code code,
        Severity severity,
        Path source,
        Path relatedSource
) {
    public ProjectContextDiagnostic {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
    }

    /** Stable diagnostic identifiers that do not expose project instruction contents. */
    public enum Code {
        INVALID_GLOBAL_DIRECTORY,
        INVALID_DISCOVERY_ROOT,
        DISCOVERY_ROOT_NOT_ANCESTOR,
        NOT_REGULAR_FILE,
        SOURCE_UNREADABLE,
        INVALID_UTF8,
        SOURCE_IDENTITY_FAILED,
        DUPLICATE_SOURCE,
        SHADOWED_WORKTREE_SOURCE,
        GIT_METADATA_INVALID,
        LOAD_LIMIT_EXCEEDED
    }

    /** Diagnostic impact. */
    public enum Severity {
        WARNING,
        ERROR
    }

}
