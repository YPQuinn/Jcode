package site.pplee.jcode.codingagent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.aiproviders.openai.OpenAiCredentials;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Resolves API-key credentials with fixed SDK, environment, then file precedence. */
public final class CredentialResolver {
    private CredentialResolver() {
    }

    public static Resolution resolve(
            List<ProviderDefinition> definitions,
            Options options
    ) {
        Objects.requireNonNull(definitions, "definitions must not be null");
        Objects.requireNonNull(options, "options must not be null");
        var credentials = new LinkedHashMap<String, ResolvedCredential>();
        var diagnostics = new ArrayList<ModelAssemblyDiagnostic>();
        var unresolved = new ArrayList<ProviderDefinition>();

        for (var definition : definitions) {
            var sdk = options.sdkCredentials().get(definition.providerId());
            if (sdk != null) {
                credentials.put(definition.providerId(), new ResolvedCredential(
                        definition.providerId(), sdk, CredentialSource.SDK));
                continue;
            }
            if (options.environmentEnabled()
                    && definition.apiKeyEnvironmentVariable().isPresent()) {
                String variable = definition.apiKeyEnvironmentVariable().orElseThrow();
                String value;
                try {
                    value = options.environment().apply(variable);
                } catch (RuntimeException failure) {
                    diagnostics.add(ModelAssemblyDiagnostic.provider(
                            ModelAssemblyDiagnostic.Code.CREDENTIAL_INVALID,
                            "environment credential lookup failed for variable " + variable,
                            definition.providerId()));
                    continue;
                }
                if (value != null) {
                    if (value.isBlank()) {
                        diagnostics.add(ModelAssemblyDiagnostic.provider(
                                ModelAssemblyDiagnostic.Code.CREDENTIAL_INVALID,
                                "environment credential is blank for variable " + variable,
                                definition.providerId()));
                    } else {
                        credentials.put(definition.providerId(), new ResolvedCredential(
                                definition.providerId(), OpenAiCredentials.apiKey(value),
                                CredentialSource.ENVIRONMENT));
                    }
                    continue;
                }
            }
            unresolved.add(definition);
        }

        Map<String, OpenAiCredentials> fileCredentials = Map.of();
        if (!unresolved.isEmpty() && options.authFile().isPresent()) {
            var fileResult = readAuthFile(options.authFile().orElseThrow(), options.objectMapper());
            fileCredentials = fileResult.credentials();
            diagnostics.addAll(fileResult.diagnostics());
        }
        for (var definition : unresolved) {
            var credential = fileCredentials.get(definition.providerId());
            if (credential == null) {
                diagnostics.add(ModelAssemblyDiagnostic.provider(
                        ModelAssemblyDiagnostic.Code.CREDENTIAL_MISSING,
                        "no API-key credential is configured for provider "
                                + definition.providerId(),
                        definition.providerId()));
            } else {
                credentials.put(definition.providerId(), new ResolvedCredential(
                        definition.providerId(), credential, CredentialSource.FILE));
            }
        }
        return new Resolution(credentials, diagnostics);
    }

    private static AuthFileResult readAuthFile(Path path, ObjectMapper mapper) {
        var normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return new AuthFileResult(Map.of(), List.of());
        }
        try {
            Path physical = normalized.toRealPath();
            if (hasUnsafePermissions(physical)) {
                return new AuthFileResult(Map.of(), List.of(ModelAssemblyDiagnostic.file(
                        ModelAssemblyDiagnostic.Code.CREDENTIAL_FILE_UNSAFE_PERMISSIONS,
                        "credential file is accessible by group or other users",
                        normalized)));
            }
            JsonNode root;
            try (var input = Files.newInputStream(physical)) {
                root = mapper.reader()
                        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                        .readTree(input);
            }
            if (root == null || !root.isObject() || root.size() != 1
                    || !root.has("providers") || !root.get("providers").isObject()) {
                throw new IllegalArgumentException(
                        "credential file must contain only an object field named providers");
            }
            var result = new LinkedHashMap<String, OpenAiCredentials>();
            root.get("providers").fields().forEachRemaining(entry -> {
                var record = entry.getValue();
                if (!record.isObject() || record.size() != 2
                        || !record.has("type") || !record.has("key")
                        || !record.get("type").isTextual()
                        || !"api_key".equals(record.get("type").textValue())
                        || !record.get("key").isTextual()
                        || record.get("key").textValue().isBlank()) {
                    throw new IllegalArgumentException(
                            "credential record is invalid for provider " + entry.getKey());
                }
                result.put(entry.getKey(),
                        OpenAiCredentials.apiKey(record.get("key").textValue()));
            });
            return new AuthFileResult(result, List.of());
        } catch (JsonProcessingException failure) {
            return new AuthFileResult(Map.of(), List.of(ModelAssemblyDiagnostic.file(
                    ModelAssemblyDiagnostic.Code.CREDENTIAL_INVALID,
                    "credential file is not valid JSON", normalized)));
        } catch (IllegalArgumentException failure) {
            return new AuthFileResult(Map.of(), List.of(ModelAssemblyDiagnostic.file(
                    ModelAssemblyDiagnostic.Code.CREDENTIAL_INVALID,
                    "credential file is invalid", normalized)));
        } catch (IOException failure) {
            return new AuthFileResult(Map.of(), List.of(ModelAssemblyDiagnostic.file(
                    ModelAssemblyDiagnostic.Code.CREDENTIAL_FILE_READ_FAILED,
                    "credential file could not be read", normalized)));
        }
    }

    private static boolean hasUnsafePermissions(Path path) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    path, LinkOption.NOFOLLOW_LINKS);
            return permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (UnsupportedOperationException ignored) {
            return false;
        }
    }

    public enum CredentialSource {
        SDK,
        ENVIRONMENT,
        FILE
    }

    /** Resolved redacted credential holder and its source. */
    public record ResolvedCredential(
            String providerId,
            OpenAiCredentials credentials,
            CredentialSource source
    ) {
        public ResolvedCredential {
            Objects.requireNonNull(providerId, "providerId must not be null");
            Objects.requireNonNull(credentials, "credentials must not be null");
            Objects.requireNonNull(source, "source must not be null");
        }
    }

    /** Explicit credential sources; no environment is read unless enabled. */
    public record Options(
            Map<String, OpenAiCredentials> sdkCredentials,
            boolean environmentEnabled,
            Function<String, String> environment,
            Optional<Path> authFile,
            ObjectMapper objectMapper
    ) {
        public Options {
            sdkCredentials = Map.copyOf(
                    Objects.requireNonNull(sdkCredentials, "sdkCredentials must not be null"));
            environment = environment == null ? ignored -> null : environment;
            Objects.requireNonNull(authFile, "authFile must not be null");
            authFile = authFile.map(path -> path.toAbsolutePath().normalize());
            Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        }

        public static Options filesOnly(Path authFile, ObjectMapper objectMapper) {
            return new Options(
                    Map.of(), false, ignored -> null,
                    Optional.ofNullable(authFile), objectMapper);
        }
    }

    public record Resolution(
            Map<String, ResolvedCredential> credentials,
            List<ModelAssemblyDiagnostic> diagnostics
    ) {
        public Resolution {
            credentials = Map.copyOf(credentials);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private record AuthFileResult(
            Map<String, OpenAiCredentials> credentials,
            List<ModelAssemblyDiagnostic> diagnostics
    ) {
        private AuthFileResult {
            credentials = Map.copyOf(credentials);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
