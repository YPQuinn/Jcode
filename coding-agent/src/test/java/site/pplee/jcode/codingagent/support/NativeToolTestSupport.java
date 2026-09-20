package site.pplee.jcode.codingagent.support;

import org.junit.jupiter.api.Assumptions;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/** Resolves explicitly injected native tools and turns smoke-profile skips into failures. */
public final class NativeToolTestSupport {
    private static final String STRICT_PROPERTY = "jcode.test.localToolsStrict";

    private NativeToolTestSupport() {
    }

    /** Return a usable Bash executable or skip when the regular test environment lacks one. */
    public static Path requireBash() {
        return requireExecutable(
                "jcode.test.bash",
                List.of(Path.of("/bin/bash")),
                "a POSIX Bash executable is required");
    }

    /** Return a usable ripgrep executable or skip when the regular test environment lacks one. */
    public static Path requireRipgrep() {
        return requireExecutable(
                "jcode.test.rg",
                List.of(
                        Path.of("/opt/homebrew/bin/rg"),
                        Path.of("/usr/local/bin/rg"),
                        Path.of("/usr/bin/rg")),
                "ripgrep is required");
    }

    /** Return the explicit file-finder executable, without downloading tools. */
    public static Path requireFd() {
        return requireExecutable("jcode.test.fd", List.of(Path.of("/opt/homebrew/bin/fd"),
                Path.of("/usr/local/bin/fd"), Path.of("/usr/bin/fd")), "fd is required");
    }

    /** Require the POSIX process facilities exercised by the native process tests. */
    public static void requirePosixProcessSupport() {
        requireCondition(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX file attributes are required");
        requireCondition(
                Files.isRegularFile(Path.of("/bin/sleep"))
                        && Files.isExecutable(Path.of("/bin/sleep")),
                "/bin/sleep is required");
    }

    /** Return whether the strict native smoke profile is active. */
    public static boolean strictSmokeRequired() {
        return Boolean.getBoolean(STRICT_PROPERTY);
    }

    private static Path requireExecutable(
            String property,
            List<Path> fallbacks,
            String unavailableMessage
    ) {
        String configured = System.getProperty(property);
        if (configured != null && !configured.isBlank()) {
            Path candidate;
            try {
                candidate = Path.of(configured);
            } catch (InvalidPathException e) {
                return unavailable(property, unavailableMessage);
            }
            if (isExecutable(candidate)) {
                return candidate;
            }
            return unavailable(property, unavailableMessage);
        }

        if (!strictSmokeRequired()) {
            for (var candidate : fallbacks) {
                if (isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return unavailable(property, unavailableMessage);
    }

    private static boolean isExecutable(Path candidate) {
        return candidate.isAbsolute()
                && Files.isRegularFile(candidate)
                && Files.isExecutable(candidate);
    }

    private static Path unavailable(String property, String message) {
        if (strictSmokeRequired()) {
            throw new AssertionError(property
                    + " must identify an executable absolute regular file in strict smoke mode");
        }
        Assumptions.assumeTrue(false, message);
        throw new AssertionError("unreachable");
    }

    private static void requireCondition(boolean condition, String message) {
        if (!condition && strictSmokeRequired()) {
            throw new AssertionError(message + " in strict smoke mode");
        }
        Assumptions.assumeTrue(condition, message);
    }
}
