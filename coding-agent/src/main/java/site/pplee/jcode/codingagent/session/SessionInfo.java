package site.pplee.jcode.codingagent.session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable summary of one valid session file. */
public record SessionInfo(
        Path path,
        UUID id,
        Path cwd,
        Optional<String> name,
        Instant created,
        Instant modified,
        long messageCount
) {
    public SessionInfo {
        path = normalize(path, "path");
        Objects.requireNonNull(id, "id must not be null");
        cwd = normalize(cwd, "cwd");
        name = Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(created, "created must not be null");
        Objects.requireNonNull(modified, "modified must not be null");
        if (messageCount < 0) {
            throw new IllegalArgumentException("messageCount must not be negative");
        }
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name + " must not be null")
                .toAbsolutePath()
                .normalize();
    }
}
