package site.pplee.jcode.codingagent.settings;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Non-secret settings or trust diagnostic. File contents are never retained. */
public record SettingsDiagnostic(
        Code code,
        String message,
        Optional<Path> path,
        Optional<SettingsField> field
) {
    public SettingsDiagnostic {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(field, "field must not be null");
        path = path.map(value -> value.toAbsolutePath().normalize());
    }

    public static SettingsDiagnostic of(Code code, String message, Path path) {
        return new SettingsDiagnostic(code, message, Optional.ofNullable(path), Optional.empty());
    }

    public static SettingsDiagnostic field(
            Code code,
            String message,
            Path path,
            SettingsField field
    ) {
        return new SettingsDiagnostic(
                code, message, Optional.ofNullable(path), Optional.ofNullable(field));
    }

    /** Stable diagnostic categories for programmatic handling. */
    public enum Code {
        SETTINGS_READ_FAILED,
        SETTINGS_INVALID,
        UNSUPPORTED_FIELD,
        PROJECT_SETTINGS_NOT_APPLIED,
        TRUST_STORE_READ_FAILED,
        TRUST_STORE_INVALID,
        TRUST_STORE_INSIDE_PROJECT,
        TRUST_STORE_UNSAFE_PERMISSIONS
    }
}
