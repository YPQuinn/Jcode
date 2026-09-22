package site.pplee.jcode.codingagent.settings;

import java.nio.file.Path;
import java.util.Objects;

/** Result of one explicit settings or trust save. */
public record SettingsSaveResult(Path path, boolean updated) {
    public SettingsSaveResult {
        Objects.requireNonNull(path, "path must not be null");
        path = path.toAbsolutePath().normalize();
    }
}
