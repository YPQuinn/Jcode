package site.pplee.jcode.server;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Local-only host settings; workspace paths are chosen by the service owner. */
public record ServerConfig(
        int port,
        Path dataDirectory,
        Path userConfigDirectory,
        Map<String, Path> workspaces,
        Set<String> allowedOrigins,
        Set<String> approvalTools,
        Duration approvalTimeout
) {
    private static final int MAX_CONFIG_BYTES = 1_048_576;
    private static final Set<String> FIELDS = Set.of("port", "dataDirectory",
            "userConfigDirectory", "workspaces", "allowedOrigins", "approvalTools",
            "approvalTimeoutSeconds");

    public ServerConfig {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        Objects.requireNonNull(dataDirectory);
        Objects.requireNonNull(userConfigDirectory);
        if (!dataDirectory.isAbsolute() || !userConfigDirectory.isAbsolute()) {
            throw new IllegalArgumentException("server directories must be absolute paths");
        }
        dataDirectory = dataDirectory.normalize();
        userConfigDirectory = userConfigDirectory.normalize();
        var checkedWorkspaces = new LinkedHashMap<String, Path>();
        Objects.requireNonNull(workspaces).forEach((id, path) -> {
            if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("invalid workspaceId");
            }
            Path directory = Objects.requireNonNull(path);
            if (!directory.isAbsolute()) {
                throw new IllegalArgumentException("workspace paths must be absolute");
            }
            if (!Files.isDirectory(directory)) {
                throw new IllegalArgumentException("workspace must be an existing directory");
            }
            try {
                checkedWorkspaces.put(id, directory.toRealPath());
            } catch (IOException failure) {
                throw new IllegalArgumentException("workspace cannot be resolved", failure);
            }
        });
        if (checkedWorkspaces.isEmpty()) {
            throw new IllegalArgumentException("at least one workspace is required");
        }
        workspaces = Map.copyOf(checkedWorkspaces);
        allowedOrigins = Set.copyOf(Objects.requireNonNull(allowedOrigins));
        if (allowedOrigins.stream().anyMatch(origin -> !validOrigin(origin))) {
            throw new IllegalArgumentException("allowedOrigins must contain exact HTTP origins");
        }
        approvalTools = Set.copyOf(Objects.requireNonNull(approvalTools));
        if (approvalTools.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("approvalTools must not contain blanks");
        }
        Objects.requireNonNull(approvalTimeout);
        if (approvalTimeout.isZero() || approvalTimeout.isNegative()
                || approvalTimeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("approvalTimeout must be positive and at most one hour");
        }
    }

    /** Read one bounded JSON object, rejecting additional roots and unknown settings. */
    public static ServerConfig load(Path file, ObjectMapper mapper) throws IOException {
        Objects.requireNonNull(file);
        Objects.requireNonNull(mapper);
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_CONFIG_BYTES + 1);
        }
        if (bytes.length > MAX_CONFIG_BYTES) {
            throw new IOException("server configuration exceeds 1 MiB");
        }
        try (JsonParser parser = mapper.createParser(bytes)) {
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw new IOException("server configuration must contain one JSON object");
            }
            var fields = root.fieldNames();
            while (fields.hasNext()) {
                if (!FIELDS.contains(fields.next())) {
                    throw new IOException("server configuration contains an unknown field");
                }
            }
            JsonNode workspaceNode = root.required("workspaces");
            if (!workspaceNode.isObject()) {
                throw new IOException("workspaces must be an object");
            }
            var workspaces = new LinkedHashMap<String, Path>();
            workspaceNode.properties().forEach(entry ->
                    workspaces.put(entry.getKey(), Path.of(text(entry.getValue()))));
            return new ServerConfig(
                    integer(root.required("port")),
                    Path.of(text(root.required("dataDirectory"))),
                    Path.of(text(root.required("userConfigDirectory"))),
                    workspaces,
                    strings(root.path("allowedOrigins")),
                    strings(root.path("approvalTools")),
                    Duration.ofSeconds(root.has("approvalTimeoutSeconds")
                            ? integer(root.get("approvalTimeoutSeconds")) : 300));
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid server configuration: " + failure.getMessage(), failure);
        }
    }

    private static String text(JsonNode value) {
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("expected nonempty text");
        }
        return value.textValue();
    }

    private static int integer(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("expected integer");
        }
        return value.intValue();
    }

    private static Set<String> strings(JsonNode value) {
        if (value.isMissingNode()) {
            return Set.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("expected string array");
        }
        return java.util.stream.StreamSupport.stream(value.spliterator(), false)
                .map(ServerConfig::text).collect(Collectors.toUnmodifiableSet());
    }

    private static boolean validOrigin(String origin) {
        try {
            var parsed = java.net.URI.create(origin);
            return ("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme()))
                    && parsed.getHost() != null
                    && parsed.getRawUserInfo() == null
                    && (parsed.getRawPath() == null || parsed.getRawPath().isEmpty())
                    && parsed.getRawQuery() == null
                    && parsed.getRawFragment() == null;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}
