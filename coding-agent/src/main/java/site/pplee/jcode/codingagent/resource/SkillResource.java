package site.pplee.jcode.codingagent.resource;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable metadata for one selected skill; its body remains on demand. */
public record SkillResource(
        String name,
        String description,
        Path filePath,
        Path baseDirectory,
        ResourceSource source,
        boolean disableModelInvocation
) {
    public SkillResource {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");
        Objects.requireNonNull(baseDirectory, "baseDirectory must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }
}
