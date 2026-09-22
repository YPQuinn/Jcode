package site.pplee.jcode.codingagent.settings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Configuration write failure that states whether atomic replacement already committed. */
public final class SettingsWriteException extends IOException {
    private final Path path;
    private final boolean committed;

    public SettingsWriteException(Path path, boolean committed, IOException cause) {
        super(committed
                ? "configuration was updated but lock/resource cleanup failed: " + path
                : "configuration was not updated: " + path,
                cause);
        this.path = Objects.requireNonNull(path, "path must not be null");
        this.committed = committed;
    }

    public Path path() {
        return path;
    }

    public boolean committed() {
        return committed;
    }
}
