package site.pplee.jcode.codingagent.resource;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable selected prompt template including the body captured at load time. */
public record PromptTemplateResource(
        String name,
        String description,
        String argumentHint,
        String content,
        Path filePath,
        ResourceSource source
) {
    public PromptTemplateResource {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }
}
