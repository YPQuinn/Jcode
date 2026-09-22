package site.pplee.jcode.codingagent.settings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
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
        Objects.requireNonNull(canonicalProject, "canonicalProject must not be null");
        var project = realPathOrNormalized(canonicalProject);
        var diagnostics = new ArrayList<SettingsDiagnostic>();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new LookupResult(ProjectTrustDecision.UNSPECIFIED, diagnostics);
        }
        try {
            Path physicalFile = file.toRealPath();
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
                        .readTree(input);
            }
            if (root == null || !root.isObject() || root.size() != 1
                    || !root.has("projects") || !root.get("projects").isObject()) {
                throw new IOException("trust store must contain only an object field named projects");
            }
            var projects = root.get("projects");
            var decisions = projects.elements();
            while (decisions.hasNext()) {
                if (!decisions.next().isBoolean()) {
                    throw new IOException("project trust decision must be boolean");
                }
            }
            var value = projects.get(project.toString());
            if (value == null) {
                return new LookupResult(ProjectTrustDecision.UNSPECIFIED, diagnostics);
            }
            if (!value.isBoolean()) {
                throw new IOException("project trust decision must be boolean");
            }
            return new LookupResult(
                    value.booleanValue() ? ProjectTrustDecision.ALLOW : ProjectTrustDecision.DENY,
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
        if (decision == null || decision == ProjectTrustDecision.UNSPECIFIED) {
            throw new IllegalArgumentException("remember requires ALLOW or DENY");
        }
        Path project = Objects.requireNonNull(
                projectDirectory, "projectDirectory must not be null").toRealPath();
        requireOutsideProject(project);
        requireSafeExistingStore();
        ConfigFileUpdater.update(file, objectMapper, true, root -> {
            var projects = projectsObject(root);
            projects.put(project.toString(), decision == ProjectTrustDecision.ALLOW);
        });
        return new SettingsSaveResult(file, true);
    }

    /** Remove the exact project's saved decision. */
    public SettingsSaveResult remove(Path projectDirectory) throws IOException {
        Path project = Objects.requireNonNull(
                projectDirectory, "projectDirectory must not be null").toRealPath();
        requireOutsideProject(project);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new SettingsSaveResult(file, false);
        }
        requireSafeExistingStore();
        ConfigFileUpdater.update(file, objectMapper, true, root ->
                projectsObject(root).remove(project.toString()));
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
        var fields = projects.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!entry.getValue().isBoolean()) {
                throw new IOException("project trust decision must be boolean");
            }
        }
        return projects;
    }

    private void requireOutsideProject(Path project) throws IOException {
        Path location = Files.exists(file.getParent())
                ? file.getParent().toRealPath() : file.getParent().toAbsolutePath().normalize();
        if (location.startsWith(project)) {
            throw new IOException("trust store must be outside the project directory");
        }
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
