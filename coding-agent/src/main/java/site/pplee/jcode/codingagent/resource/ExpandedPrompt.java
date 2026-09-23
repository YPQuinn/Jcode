package site.pplee.jcode.codingagent.resource;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A fixed expansion result that can be submitted through the ordinary prompt API. */
public record ExpandedPrompt(
        String text,
        long resourceRevision,
        ResourceType resourceType,
        String resourceName,
        Path sourcePath,
        List<ResourceDiagnostic> diagnostics
) {
    public ExpandedPrompt {
        Objects.requireNonNull(text, "text must not be null");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }

    public Optional<ResourceType> sourceType() {
        return Optional.ofNullable(resourceType);
    }

    public Optional<String> sourceName() {
        return Optional.ofNullable(resourceName);
    }

    public Optional<Path> source() {
        return Optional.ofNullable(sourcePath);
    }
}
