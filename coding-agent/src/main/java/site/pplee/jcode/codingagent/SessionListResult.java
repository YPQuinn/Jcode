package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.session.SessionInfo;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable sessions and per-file diagnostics from one explicit directory scan. */
public record SessionListResult(
        List<SessionInfo> sessions,
        List<SessionFileDiagnostic> diagnostics
) {
    public SessionListResult {
        sessions = List.copyOf(Objects.requireNonNull(sessions, "sessions must not be null"));
        diagnostics = List.copyOf(Objects.requireNonNull(
                diagnostics, "diagnostics must not be null"));
    }

    /** First session in the latest-first result, if one exists. */
    public Optional<SessionInfo> latest() {
        return sessions.stream().findFirst();
    }
}
