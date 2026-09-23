package site.pplee.jcode.codingagent.settings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reads exact canonical-project decisions from the explicit user trust file. */
public final class ProjectTrustStore {
    public static final String FILE_NAME = "trust.json";

    private final Path file;
    private final ObjectMapper objectMapper;

    public ProjectTrustStore(Path userConfigDirectory, ObjectMapper objectMapper) {
        Objects.requireNonNull(userConfigDirectory, "userConfigDirectory must not be null");
        file = userConfigDirectory.toAbsolutePath().normalize().resolve(FILE_NAME);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public Path file() {
        return file;
    }

    /** Missing stores and missing keys produce UNSPECIFIED without creating files. */
    public LookupResult lookup(Path canonicalProject) {
        return lookup(canonicalProject, ProjectTrustScope.SETTINGS);
    }

    /** Look up one independent scope; legacy booleans apply only to settings. */
    public LookupResult lookup(Path canonicalProject, ProjectTrustScope scope) {
        Objects.requireNonNull(canonicalProject, "canonicalProject must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        var project = realPathOrNormalized(canonicalProject);
        var diagnostics = new ArrayList<SettingsDiagnostic>();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new LookupResult(ProjectTrustDecision.UNSPECIFIED, diagnostics);
        }
        try {
            Path physicalFile = physicalStoreLocation();
            if (physicalFile.startsWith(project)) {
                diagnostics.add(SettingsDiagnostic.of(
                        SettingsDiagnostic.Code.TRUST_STORE_INSIDE_PROJECT,
                        "trust store is inside the project and cannot authorize project settings",
                        file));
                return new LookupResult(ProjectTrustDecision.UNSPECIFIED, diagnostics);
            }
            if (isGroupOrOtherWritable(physicalFile)) {
                diagnostics.add(SettingsDiagnostic.of(
                        SettingsDiagnostic.Code.TRUST_STORE_UNSAFE_PERMISSIONS,
                        "trust store is writable by group or other users",
                        file));
                return new LookupResult(ProjectTrustDecision.UNSPECIFIED, diagnostics);
            }
            JsonNode root;
            try (var input = Files.newInputStream(physicalFile)) {
                root = objectMapper.reader()
                        .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(input);
            }
            if (root == null || !root.isObject() || root.size() != 1
                    || !root.has("projects") || !root.get("projects").isObject()) {
                throw new IOException("trust store must contain only an object field named projects");
            }
            var projects = root.get("projects");
            validateProjects(projects);
            var value = projects.get(project.toString());
            if (value == null) {
                return new LookupResult(ProjectTrustDecision.UNSPECIFIED, diagnostics);
            }
            if (value.isBoolean()) {
                if (scope != ProjectTrustScope.SETTINGS) {
                    return new LookupResult(ProjectTrustDecision.UNSPECIFIED, diagnostics);
                }
                return new LookupResult(
                        value.booleanValue() ? ProjectTrustDecision.ALLOW : ProjectTrustDecision.DENY,
                        diagnostics);
            }
            var scoped = value.get(scope.jsonField());
            if (scoped == null) {
                return new LookupResult(ProjectTrustDecision.UNSPECIFIED, diagnostics);
            }
            return new LookupResult(
                    scoped.booleanValue() ? ProjectTrustDecision.ALLOW : ProjectTrustDecision.DENY,
                    diagnostics);
        } catch (IOException | RuntimeException failure) {
            diagnostics.add(SettingsDiagnostic.of(
                    SettingsDiagnostic.Code.TRUST_STORE_INVALID,
                    "could not use trust store because it is invalid or unreadable",
                    file));
            return new LookupResult(ProjectTrustDecision.UNSPECIFIED, diagnostics);
        }
    }

    /** Persist an exact ALLOW or DENY decision without loading project settings. */
    public SettingsSaveResult remember(
            Path projectDirectory,
            ProjectTrustDecision decision
    ) throws IOException {
        return remember(projectDirectory, ProjectTrustScope.SETTINGS, decision);
    }

    /** Persist one scope while preserving every other decision for the project. */
    public SettingsSaveResult remember(
            Path projectDirectory,
            ProjectTrustScope scope,
            ProjectTrustDecision decision
    ) throws IOException {
        if (decision == null || decision == ProjectTrustDecision.UNSPECIFIED) {
            throw new IllegalArgumentException("remember requires ALLOW or DENY");
        }
        Objects.requireNonNull(scope, "scope must not be null");
        Path project = Objects.requireNonNull(
                projectDirectory, "projectDirectory must not be null").toRealPath();
        requireOutsideProject(project);
        requireSafeExistingStore();
        ConfigFileUpdater.update(file, objectMapper, true, root -> {
            var projects = projectsObject(root);
            var existing = projects.get(project.toString());
            var scoped = objectMapper.createObjectNode();
            if (existing != null && existing.isBoolean()) {
                scoped.put(ProjectTrustScope.SETTINGS.jsonField(), existing.booleanValue());
            } else if (existing != null) {
                existing.fields().forEachRemaining(field -> scoped.set(field.getKey(), field.getValue()));
            }
            scoped.put(scope.jsonField(), decision == ProjectTrustDecision.ALLOW);
            projects.set(project.toString(), scoped);
        });
        return new SettingsSaveResult(file, true);
    }

    /** Remove the exact project's saved decision. */
    public SettingsSaveResult remove(Path projectDirectory) throws IOException {
        return remove(projectDirectory, ProjectTrustScope.SETTINGS);
    }

    /** Remove one scope without discarding another saved scope. */
    public SettingsSaveResult remove(Path projectDirectory, ProjectTrustScope scope) throws IOException {
        Objects.requireNonNull(scope, "scope must not be null");
        Path project = Objects.requireNonNull(
                projectDirectory, "projectDirectory must not be null").toRealPath();
        requireOutsideProject(project);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new SettingsSaveResult(file, false);
        }
        requireSafeExistingStore();
        ConfigFileUpdater.update(file, objectMapper, true, root -> {
            var projects = projectsObject(root);
            var existing = projects.get(project.toString());
            if (existing == null) {
                return;
            }
            if (existing.isBoolean()) {
                if (scope == ProjectTrustScope.SETTINGS) {
                    projects.remove(project.toString());
                }
                return;
            }
            var scoped = (com.fasterxml.jackson.databind.node.ObjectNode) existing;
            scoped.remove(scope.jsonField());
            if (scoped.isEmpty()) {
                projects.remove(project.toString());
            }
        });
        return new SettingsSaveResult(file, true);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode projectsObject(
            com.fasterxml.jackson.databind.node.ObjectNode root
    ) throws IOException {
        if (root.isEmpty()) {
            return root.putObject("projects");
        }
        if (root.size() != 1 || !(root.get("projects")
                instanceof com.fasterxml.jackson.databind.node.ObjectNode projects)) {
            throw new IOException("trust store must contain only an object field named projects");
        }
        validateProjects(projects);
        return projects;
    }

    private static void validateProjects(JsonNode projects) throws IOException {
        var fields = projects.fields();
        while (fields.hasNext()) {
            var value = fields.next().getValue();
            if (value.isBoolean()) {
                continue;
            }
            if (!value.isObject()) {
                throw new IOException("project trust decision must be boolean or scoped object");
            }
            var scopes = value.fields();
            while (scopes.hasNext()) {
                var scope = scopes.next();
                if (!(scope.getKey().equals(ProjectTrustScope.SETTINGS.jsonField())
                        || scope.getKey().equals(ProjectTrustScope.TEXT_RESOURCES.jsonField()))
                        || !scope.getValue().isBoolean()) {
                    throw new IOException("project trust scope must be a known boolean field");
                }
            }
        }
    }

    private void requireOutsideProject(Path project) throws IOException {
        if (physicalStoreLocation().startsWith(project)) {
            throw new IOException("trust store must be outside the project directory");
        }
    }

    private Path physicalStoreLocation() throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return file.toRealPath();
        }

        Path parent = file.getParent();
        var missingSegments = new ArrayDeque<Path>();
        while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            missingSegments.addFirst(parent.getFileName());
            parent = parent.getParent();
        }
        if (parent == null) {
            return file.toAbsolutePath().normalize();
        }

        Path physicalParent = parent.toRealPath();
        for (var segment : missingSegments) {
            physicalParent = physicalParent.resolve(segment);
        }
        return physicalParent.resolve(file.getFileName()).normalize();
    }

    private void requireSafeExistingStore() throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && isGroupOrOtherWritable(file.toRealPath())) {
            throw new IOException("trust store is writable by group or other users");
        }
    }

    private static Path realPathOrNormalized(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | SecurityException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static boolean isGroupOrOtherWritable(Path path) throws IOException {
        try {
            var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            return permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (UnsupportedOperationException ignored) {
            return false;
        }
    }

    public record LookupResult(
            ProjectTrustDecision decision,
            List<SettingsDiagnostic> diagnostics
    ) {
        public LookupResult {
            Objects.requireNonNull(decision, "decision must not be null");
            diagnostics = List.copyOf(
                    Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
        }
    }
}
