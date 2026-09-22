package site.pplee.jcode.codingagent.settings;

import java.io.IOException;
import java.nio.file.Path;

/** Immediate failure to acquire the short-lived lock for a settings target. */
public final class SettingsFileLockException extends IOException {
    private final Path path;

    public SettingsFileLockException(Path path, Throwable cause) {
        super("settings file is already being updated: " + path, cause);
        this.path = path;
    }

    public Path path() {
        return path;
    }
}
