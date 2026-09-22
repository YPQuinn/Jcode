package site.pplee.jcode.codingagent.model;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Non-secret provider, credential, catalog, or selection diagnostic. */
public record ModelAssemblyDiagnostic(
        Code code,
        String message,
        Optional<String> providerId,
        Optional<Path> path
) {
    public ModelAssemblyDiagnostic {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(path, "path must not be null");
        path = path.map(value -> value.toAbsolutePath().normalize());
    }

    public static ModelAssemblyDiagnostic provider(Code code, String message, String providerId) {
        return new ModelAssemblyDiagnostic(
                code, message, Optional.ofNullable(providerId), Optional.empty());
    }

    public static ModelAssemblyDiagnostic file(Code code, String message, Path path) {
        return new ModelAssemblyDiagnostic(
                code, message, Optional.empty(), Optional.ofNullable(path));
    }

    public enum Code {
        MODELS_READ_FAILED,
        MODELS_INVALID,
        CREDENTIAL_MISSING,
        CREDENTIAL_INVALID,
        CREDENTIAL_FILE_READ_FAILED,
        CREDENTIAL_FILE_UNSAFE_PERMISSIONS,
        PROVIDER_NOT_ASSEMBLED,
        MODEL_UNSUPPORTED,
        MODEL_AUTH_UNCONFIGURED,
        HISTORICAL_MODEL_UNAVAILABLE,
        FALLBACK_SELECTED
    }
}
