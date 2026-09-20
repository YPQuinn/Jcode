package site.pplee.jcode.codingagent.context;

import java.nio.file.Path;
import java.util.Objects;

/** Bounded structured diagnostic produced during project instruction discovery. */
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

    /** Stable diagnostic identifiers that do not expose exception text or contents. */
    public enum Code {
        INVALID_GLOBAL_DIRECTORY,
        INVALID_DISCOVERY_ROOT,
        DISCOVERY_ROOT_NOT_ANCESTOR,
        NOT_REGULAR_FILE,
        SOURCE_UNREADABLE,
        SOURCE_TOO_LARGE,
        INVALID_UTF8,
        SOURCE_IDENTITY_FAILED,
        SOURCE_CHANGED,
        DUPLICATE_SOURCE,
        SHADOWED_WORKTREE_SOURCE,
        UNSAFE_CONTENT,
        PATH_TOO_LONG,
        GIT_METADATA_INVALID,
        LOAD_LIMIT_EXCEEDED,
        LOAD_DEADLINE_EXCEEDED
    }

    /** Diagnostic impact. */
    public enum Severity {
        WARNING,
        ERROR
    }

    @Override
    public String toString() {
        return "ProjectContextDiagnostic[code=" + code + ", severity=" + severity
                + ", source=" + (source == null ? "absent" : "present")
                + ", relatedSource=" + (relatedSource == null ? "absent" : "present") + ']';
    }
}
