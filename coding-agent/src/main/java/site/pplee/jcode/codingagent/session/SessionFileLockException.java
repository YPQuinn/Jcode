package site.pplee.jcode.codingagent.session;

import java.io.IOException;
import java.nio.file.Path;

/** Indicates that an opened session file cannot obtain exclusive writer ownership. */
public final class SessionFileLockException extends IOException {
    private final Path path;

    /** Create a lock-ownership failure for the supplied path. */
    public SessionFileLockException(Path path, Throwable cause) {
        super("session file is already owned or does not support an exclusive lock: " + path, cause);
        this.path = path;
    }

    /** Session file that could not be exclusively owned. */
    public Path path() {
        return path;
    }
}
