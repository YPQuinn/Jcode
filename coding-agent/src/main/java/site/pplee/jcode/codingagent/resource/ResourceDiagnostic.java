package site.pplee.jcode.codingagent.resource;

import java.nio.file.Path;
import java.util.Objects;

/** Structured, content-free explanation of a resource discovery problem. */
public record ResourceDiagnostic(
        Code code,
        ResourceType resourceType,
        ResourceSource source,
        Path path,
        String message,
        String name,
        Path winnerPath,
        Path loserPath
) {
    public ResourceDiagnostic {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    public static ResourceDiagnostic of(
            Code code,
            ResourceType type,
            ResourceSource source,
            Path path,
            String message
    ) {
        return new ResourceDiagnostic(code, type, source, path, message, null, null, null);
    }

    public static ResourceDiagnostic collision(
            ResourceType type,
            ResourceSource source,
            String name,
            Path winner,
            Path loser
    ) {
        return new ResourceDiagnostic(
                Code.COLLISION, type, source, loser,
                "resource name is already provided by an earlier source",
                name, winner, loser);
    }

    public enum Code {
        PARSE_FAILURE,
        INVALID_METADATA,
        COLLISION,
        DUPLICATE_PHYSICAL_PATH,
        UNSUPPORTED_RESOURCE,
        UNTRUSTED_PROJECT_RESOURCE,
        IO_FAILURE,
        MISSING_ARGUMENT,
        MODEL_READING_UNAVAILABLE
    }
}
