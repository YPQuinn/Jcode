package site.pplee.jcode.codingagent.context;

import java.nio.file.Path;
import java.util.Objects;

/** Explicit immutable configuration for project instruction discovery. */
public record ProjectContextConfig(
        boolean enabled,
        Path globalDirectory,
        Path discoveryRoot,
        ProjectContextFailureMode failureMode
) {
    public ProjectContextConfig {
        failureMode = failureMode == null ? ProjectContextFailureMode.WARN_AND_SKIP : failureMode;
        if (!enabled && (globalDirectory != null || discoveryRoot != null)) {
            throw new IllegalArgumentException("disabled project context cannot define discovery directories");
        }
        requireAbsolute(globalDirectory, "globalDirectory");
        requireAbsolute(discoveryRoot, "discoveryRoot");
    }

    /** Return a configuration that performs no additional discovery I/O. */
    public static ProjectContextConfig disabled() {
        return new ProjectContextConfig(false, null, null, ProjectContextFailureMode.WARN_AND_SKIP);
    }

    /** Discover project instructions up to the file-system root. */
    public static ProjectContextConfig project() {
        return new ProjectContextConfig(true, null, null, ProjectContextFailureMode.WARN_AND_SKIP);
    }

    /** Discover project instructions up to an explicit inclusive configured-path ancestor. */
    public static ProjectContextConfig project(Path discoveryRoot) {
        return new ProjectContextConfig(true, null,
                Objects.requireNonNull(discoveryRoot, "discoveryRoot must not be null"),
                ProjectContextFailureMode.WARN_AND_SKIP);
    }

    /** Discover explicit global instructions followed by project instructions. */
    public static ProjectContextConfig projectAndGlobal(Path globalDirectory) {
        return new ProjectContextConfig(true,
                Objects.requireNonNull(globalDirectory, "globalDirectory must not be null"),
                null, ProjectContextFailureMode.WARN_AND_SKIP);
    }

    /** Discover global and project instructions with an explicit project ancestor bound. */
    public static ProjectContextConfig projectAndGlobal(Path globalDirectory, Path discoveryRoot) {
        return new ProjectContextConfig(true,
                Objects.requireNonNull(globalDirectory, "globalDirectory must not be null"),
                Objects.requireNonNull(discoveryRoot, "discoveryRoot must not be null"),
                ProjectContextFailureMode.WARN_AND_SKIP);
    }

    private static void requireAbsolute(Path path, String name) {
        if (path != null && !path.isAbsolute()) {
            throw new IllegalArgumentException(name + " must be absolute");
        }
    }

    @Override
    public String toString() {
        return "ProjectContextConfig[enabled=" + enabled
                + ", globalDirectory=" + (globalDirectory == null ? "absent" : "present")
                + ", discoveryRoot=" + (discoveryRoot == null ? "absent" : "present")
                + ", failureMode=" + failureMode + ']';
    }
}
