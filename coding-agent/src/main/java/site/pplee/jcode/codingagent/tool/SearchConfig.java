package site.pplee.jcode.codingagent.tool;

import java.nio.file.Path;
import java.util.Map;

/**
 * Explicit rg/fd executables. Only enabled tools require their executable.
 * A null environment inherits the host environment; a map replaces it.
 */
public record SearchConfig(
        Path executable,
        Path findExecutable,
        Map<String, String> environment
) {
    public SearchConfig {
        if (executable != null) {
            executable = BashConfig.absolutePath(executable, "executable");
        }
        if (executable == null && findExecutable == null) {
            throw new IllegalArgumentException("at least one search executable is required");
        }
        if (findExecutable != null) {
            findExecutable = BashConfig.absolutePath(findExecutable, "findExecutable");
        }
        environment = environment == null ? null : BashConfig.environmentSnapshot(environment);
    }

    /** Configure grep only; a find executable must be supplied before enabling find. */
    public SearchConfig(Path executable, Map<String, String> environment) {
        this(executable, null, environment);
    }

    /** Return the explicitly configured text search executable. */
    Path requireGrepExecutable() {
        if (executable == null) {
            throw new IllegalArgumentException("grep requires an explicit rg executable");
        }
        return executable;
    }

    /** Return the explicitly configured file finder, or reject an incomplete profile. */
    Path requireFindExecutable() {
        if (findExecutable == null) {
            throw new IllegalArgumentException("find requires an explicit fd executable");
        }
        return findExecutable;
    }

    @Override
    public String toString() {
        return "SearchConfig[executable=redacted, findExecutable=redacted, environment=redacted]";
    }
}
