package site.pplee.jcode.codingagent.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.ignore.IgnoreNode;
import site.pplee.jcode.codingagent.context.ProjectContextSnapshot;
import site.pplee.jcode.codingagent.settings.ProjectTrustDecision;
import site.pplee.jcode.codingagent.settings.ProjectTrustScope;
import site.pplee.jcode.codingagent.settings.ProjectTrustStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One-shot loader for the complete immutable text-resource snapshot. */
public final class ResourceLoader {
    private static final List<String> IGNORE_FILES = List.of(".gitignore", ".ignore", ".fdignore");
    private static final Set<String> UNSUPPORTED_SKILL_FIELDS = Set.of(
            "allowed-tools", "context", "agent", "model", "install", "installation");

    private final FrontmatterParser frontmatter = new FrontmatterParser();

    /** Load a complete candidate. Required selected SYSTEM files fail the whole operation. */
    public ResourceSnapshot load(Request request) {
        Objects.requireNonNull(request, "request must not be null");
        var diagnostics = new ArrayList<ResourceDiagnostic>();
        if (!request.config().enabled()) {
            return new ResourceSnapshot(
                    request.revision(), List.of(), List.of(),
                    request.customSystemPrompt(), request.appendSystemPrompt(),
                    request.projectContext(), diagnostics);
        }

        boolean projectAllowed = projectTextResourcesAllowed(request, diagnostics);
        var skillCandidates = new ArrayList<Candidate<SkillResource>>();
        var templateCandidates = new ArrayList<Candidate<PromptTemplateResource>>();

        if (request.config().includeDefaults()) {
            request.config().userDirectory().ifPresent(user -> {
                skillCandidates.addAll(scanSkills(user.resolve("skills"), ResourceSource.USER, false, diagnostics));
                templateCandidates.addAll(scanTemplates(user.resolve("prompts"), ResourceSource.USER, false, diagnostics));
            });
            Path projectRoot = request.workingDirectory().resolve(".jcode");
            if (projectAllowed) {
                skillCandidates.addAll(scanSkills(
                        projectRoot.resolve("skills"), ResourceSource.PROJECT, false, diagnostics));
                templateCandidates.addAll(scanTemplates(
                        projectRoot.resolve("prompts"), ResourceSource.PROJECT, false, diagnostics));
            } else if (hasProjectTextResources(projectRoot)) {
                diagnostics.add(ResourceDiagnostic.of(
                        ResourceDiagnostic.Code.UNTRUSTED_PROJECT_RESOURCE,
                        ResourceType.SKILL,
                        ResourceSource.PROJECT,
                        projectRoot,
                        "project text resources were not loaded because the scope is not authorized"));
            }
        }

        for (var path : request.config().skillPaths()) {
            skillCandidates.addAll(scanSkills(path, ResourceSource.EXPLICIT, true, diagnostics));
        }
        for (var path : request.config().promptPaths()) {
            templateCandidates.addAll(scanTemplates(path, ResourceSource.EXPLICIT, true, diagnostics));
        }

        var skills = select(skillCandidates, ResourceType.SKILL, SkillResource::name, diagnostics);
        var templates = select(
                templateCandidates, ResourceType.PROMPT_TEMPLATE,
                PromptTemplateResource::name, diagnostics);
        if (!request.readToolEnabled() && !request.bashToolEnabled()
                && skills.stream().anyMatch(skill -> !skill.disableModelInvocation())) {
            diagnostics.add(ResourceDiagnostic.of(
                    ResourceDiagnostic.Code.MODEL_READING_UNAVAILABLE,
                    ResourceType.SKILL,
                    ResourceSource.EXPLICIT,
                    null,
                    "skills cannot be advertised because no built-in read or bash tool is enabled"));
        }

        String system = selectPromptFile(
                request.customSystemPrompt(), request.config().systemPromptFile().orElse(null),
                "SYSTEM.md", ResourceType.SYSTEM_PROMPT, request, projectAllowed);
        String append = selectPromptFile(
                request.appendSystemPrompt(), request.config().appendSystemPromptFile().orElse(null),
                "APPEND_SYSTEM.md", ResourceType.APPEND_SYSTEM_PROMPT, request, projectAllowed);
        return new ResourceSnapshot(
                request.revision(), skills, templates, system, append,
                request.projectContext(), diagnostics);
    }

    private boolean projectTextResourcesAllowed(
            Request request,
            List<ResourceDiagnostic> diagnostics
    ) {
        if (request.config().projectTextResources() != ProjectTrustDecision.UNSPECIFIED) {
            return request.config().projectTextResources() == ProjectTrustDecision.ALLOW;
        }
        if (request.config().trustStoreDirectory().isEmpty()) {
            return false;
        }
        var lookup = new ProjectTrustStore(
                request.config().trustStoreDirectory().orElseThrow(), request.objectMapper())
                .lookup(request.workingDirectory(), ProjectTrustScope.TEXT_RESOURCES);
        for (var ignored : lookup.diagnostics()) {
            diagnostics.add(ResourceDiagnostic.of(
                    ResourceDiagnostic.Code.IO_FAILURE,
                    ResourceType.SKILL,
                    ResourceSource.PROJECT,
                    request.config().trustStoreDirectory().orElseThrow().resolve(ProjectTrustStore.FILE_NAME),
                    "project text-resource trust could not be determined from the store"));
        }
        return lookup.decision() == ProjectTrustDecision.ALLOW;
    }

    private String selectPromptFile(
            String inline,
            Path explicit,
            String defaultName,
            ResourceType type,
            Request request,
            boolean projectAllowed
    ) {
        if (inline != null) {
            return inline;
        }
        if (explicit != null) {
            return readRequired(explicit, type);
        }
        if (!request.config().includeDefaults()) {
            return null;
        }
        if (projectAllowed) {
            Path project = request.workingDirectory().resolve(".jcode").resolve(defaultName);
            if (Files.exists(project)) {
                return readRequired(project, type);
            }
        }
        if (request.config().userDirectory().isPresent()) {
            Path user = request.config().userDirectory().orElseThrow().resolve(defaultName);
            if (Files.exists(user)) {
                return readRequired(user, type);
            }
        }
        return null;
    }

    private static String readRequired(Path path, ResourceType type) {
        try {
            if (!Files.isRegularFile(path)) {
                throw new IOException("selected path is not a regular file");
            }
            return ResourceTextReader.read(path);
        } catch (IOException | SecurityException failure) {
            throw new ResourceLoadException("could not read selected " + type + " file: " + path, failure);
        }
    }

    private List<Candidate<SkillResource>> scanSkills(
            Path path,
            ResourceSource source,
            boolean explicit,
            List<ResourceDiagnostic> diagnostics
    ) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (explicit) {
                diagnostics.add(ResourceDiagnostic.of(
                        ResourceDiagnostic.Code.IO_FAILURE, ResourceType.SKILL, source, path,
                        "explicit skill path does not exist"));
            }
            return List.of();
        }
        var result = new ArrayList<Candidate<SkillResource>>();
        try {
            if (Files.isRegularFile(path)) {
                loadSkill(path, source, diagnostics).ifPresent(result::add);
            } else if (Files.isDirectory(path)) {
                scanSkillDirectory(
                        path, path, source, true, List.of(), new HashSet<>(), result, diagnostics);
            } else if (explicit) {
                diagnostics.add(ResourceDiagnostic.of(
                        ResourceDiagnostic.Code.IO_FAILURE, ResourceType.SKILL, source, path,
                        "explicit skill path is neither a file nor a directory"));
            }
        } catch (IOException | SecurityException failure) {
            diagnostics.add(ResourceDiagnostic.of(
                    ResourceDiagnostic.Code.IO_FAILURE, ResourceType.SKILL, source, path,
                    "skill path could not be scanned"));
        }
        return result;
    }

    private void scanSkillDirectory(
            Path root,
            Path directory,
            ResourceSource source,
            boolean includeDirectMarkdown,
            List<IgnoreLayer> inherited,
            Set<Path> visitedDirectories,
            List<Candidate<SkillResource>> result,
            List<ResourceDiagnostic> diagnostics
    ) throws IOException {
        Path physicalDirectory = directory.toRealPath();
        if (!visitedDirectories.add(physicalDirectory)) {
            return;
        }
        var layers = new ArrayList<>(inherited);
        addIgnoreLayers(directory, layers, diagnostics, source);
        List<Path> children = sortedChildren(directory);
        for (var child : children) {
            if (child.getFileName().toString().equals("SKILL.md")
                    && Files.isRegularFile(child)
                    && !ignored(child, false, layers)) {
                loadSkill(child, source, diagnostics).ifPresent(result::add);
                return;
            }
        }
        if (includeDirectMarkdown) {
            for (var child : children) {
                String name = child.getFileName().toString();
                if (visible(name) && name.endsWith(".md") && Files.isRegularFile(child)
                        && !ignored(child, false, layers)) {
                    loadSkill(child, source, diagnostics).ifPresent(result::add);
                }
            }
        }
        for (var child : children) {
            String name = child.getFileName().toString();
            if (!visible(name) || name.equals("node_modules") || !Files.isDirectory(child)
                    || ignored(child, true, layers)) {
                continue;
            }
            scanSkillDirectory(
                    root, child, source, false, layers, visitedDirectories, result, diagnostics);
        }
    }

    private java.util.Optional<Candidate<SkillResource>> loadSkill(
            Path file,
            ResourceSource source,
            List<ResourceDiagnostic> diagnostics
    ) {
        FrontmatterParser.Parsed parsed;
        try {
            parsed = frontmatter.parse(ResourceTextReader.read(file));
        } catch (IOException | RuntimeException failure) {
            diagnostics.add(ResourceDiagnostic.of(
                    failure instanceof IOException ? ResourceDiagnostic.Code.IO_FAILURE
                            : ResourceDiagnostic.Code.PARSE_FAILURE,
                    ResourceType.SKILL, source, file, "skill could not be read or parsed"));
            return java.util.Optional.empty();
        }
        var metadata = parsed.metadata();
        String name;
        Object configuredName = metadata.get("name");
        if (configuredName == null || configuredName instanceof String text && text.isBlank()) {
            name = file.getParent().getFileName().toString();
        } else if (configuredName instanceof String text) {
            name = text;
        } else {
            invalid(diagnostics, ResourceType.SKILL, source, file, "skill name must be a string");
            return java.util.Optional.empty();
        }
        Object descriptionValue = metadata.get("description");
        if (!(descriptionValue instanceof String description) || description.isBlank()) {
            invalid(diagnostics, ResourceType.SKILL, source, file,
                    "skill description must be a non-empty string");
            return java.util.Optional.empty();
        }
        boolean disabled = false;
        if (metadata.containsKey("disable-model-invocation")) {
            if (!(metadata.get("disable-model-invocation") instanceof Boolean value)) {
                invalid(diagnostics, ResourceType.SKILL, source, file,
                        "disable-model-invocation must be a boolean");
                return java.util.Optional.empty();
            }
            disabled = (Boolean) metadata.get("disable-model-invocation");
        }
        validateSkillGuidance(name, description, file, source, diagnostics);
        for (var field : UNSUPPORTED_SKILL_FIELDS) {
            if (metadata.containsKey(field)) {
                diagnostics.add(ResourceDiagnostic.of(
                        ResourceDiagnostic.Code.UNSUPPORTED_RESOURCE,
                        ResourceType.SKILL, source, file,
                        "skill metadata field is not supported: " + field));
            }
        }
        var skill = new SkillResource(name, description, file.toAbsolutePath().normalize(),
                file.toAbsolutePath().normalize().getParent(), source, disabled);
        return java.util.Optional.of(new Candidate<>(skill, file, physical(file)));
    }

    private List<Candidate<PromptTemplateResource>> scanTemplates(
            Path path,
            ResourceSource source,
            boolean explicit,
            List<ResourceDiagnostic> diagnostics
    ) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (explicit) {
                diagnostics.add(ResourceDiagnostic.of(
                        ResourceDiagnostic.Code.IO_FAILURE, ResourceType.PROMPT_TEMPLATE,
                        source, path, "explicit prompt-template path does not exist"));
            }
            return List.of();
        }
        var files = new ArrayList<Path>();
        try {
            if (Files.isRegularFile(path)) {
                files.add(path);
            } else if (Files.isDirectory(path)) {
                for (var child : sortedChildren(path)) {
                    if (Files.isRegularFile(child) && child.getFileName().toString().endsWith(".md")) {
                        files.add(child);
                    }
                }
            } else if (explicit) {
                diagnostics.add(ResourceDiagnostic.of(
                        ResourceDiagnostic.Code.IO_FAILURE, ResourceType.PROMPT_TEMPLATE,
                        source, path, "explicit prompt-template path is neither a file nor a directory"));
            }
        } catch (IOException | SecurityException failure) {
            diagnostics.add(ResourceDiagnostic.of(
                    ResourceDiagnostic.Code.IO_FAILURE, ResourceType.PROMPT_TEMPLATE,
                    source, path, "prompt-template path could not be scanned"));
        }
        var result = new ArrayList<Candidate<PromptTemplateResource>>();
        for (var file : files) {
            loadTemplate(file, source, diagnostics).ifPresent(result::add);
        }
        return result;
    }

    private java.util.Optional<Candidate<PromptTemplateResource>> loadTemplate(
            Path file,
            ResourceSource source,
            List<ResourceDiagnostic> diagnostics
    ) {
        FrontmatterParser.Parsed parsed;
        try {
            parsed = frontmatter.parse(ResourceTextReader.read(file));
        } catch (IOException | RuntimeException failure) {
            diagnostics.add(ResourceDiagnostic.of(
                    failure instanceof IOException ? ResourceDiagnostic.Code.IO_FAILURE
                            : ResourceDiagnostic.Code.PARSE_FAILURE,
                    ResourceType.PROMPT_TEMPLATE, source, file,
                    "prompt template could not be read or parsed"));
            return java.util.Optional.empty();
        }
        Object descriptionValue = parsed.metadata().get("description");
        if (descriptionValue != null && !(descriptionValue instanceof String)) {
            invalid(diagnostics, ResourceType.PROMPT_TEMPLATE, source, file,
                    "template description must be a string");
            return java.util.Optional.empty();
        }
        Object hintValue = parsed.metadata().get("argument-hint");
        if (hintValue != null && !(hintValue instanceof String)) {
            invalid(diagnostics, ResourceType.PROMPT_TEMPLATE, source, file,
                    "template argument-hint must be a string");
            return java.util.Optional.empty();
        }
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(".md")) {
            diagnostics.add(ResourceDiagnostic.of(
                    ResourceDiagnostic.Code.UNSUPPORTED_RESOURCE,
                    ResourceType.PROMPT_TEMPLATE, source, file,
                    "prompt template must use the .md extension"));
            return java.util.Optional.empty();
        }
        String name = fileName.substring(0, fileName.length() - 3);
        String description = descriptionValue instanceof String text && !text.isBlank()
                ? text : firstLineDescription(parsed.body());
        var template = new PromptTemplateResource(
                name, description, (String) hintValue, parsed.body(),
                file.toAbsolutePath().normalize(), source);
        return java.util.Optional.of(new Candidate<>(template, file, physical(file)));
    }

    private static <T> List<T> select(
            List<Candidate<T>> candidates,
            ResourceType type,
            java.util.function.Function<T, String> name,
            List<ResourceDiagnostic> diagnostics
    ) {
        var byName = new LinkedHashMap<String, Candidate<T>>();
        var physical = new HashSet<Path>();
        for (var candidate : candidates) {
            if (physical.contains(candidate.physicalPath())) {
                diagnostics.add(ResourceDiagnostic.of(
                        ResourceDiagnostic.Code.DUPLICATE_PHYSICAL_PATH,
                        type, source(candidate.value()), candidate.discoveredPath(),
                        "resource resolves to a physical file that was already accepted"));
                continue;
            }
            String candidateName = name.apply(candidate.value());
            var winner = byName.putIfAbsent(candidateName, candidate);
            if (winner != null) {
                diagnostics.add(ResourceDiagnostic.collision(
                        type, source(candidate.value()), candidateName,
                        winner.discoveredPath(), candidate.discoveredPath()));
            } else {
                physical.add(candidate.physicalPath());
            }
        }
        return byName.values().stream().map(Candidate::value).toList();
    }

    private static ResourceSource source(Object resource) {
        if (resource instanceof SkillResource skill) {
            return skill.source();
        }
        return ((PromptTemplateResource) resource).source();
    }

    private static void validateSkillGuidance(
            String name,
            String description,
            Path file,
            ResourceSource source,
            List<ResourceDiagnostic> diagnostics
    ) {
        if (name.length() > 64 || !name.matches("[a-z0-9-]+")
                || name.startsWith("-") || name.endsWith("-") || name.contains("--")) {
            invalid(diagnostics, ResourceType.SKILL, source, file,
                    "skill name does not follow the recommended lowercase hyphenated form");
        }
        if (description.length() > 1024) {
            invalid(diagnostics, ResourceType.SKILL, source, file,
                    "skill description exceeds 1024 characters");
        }
    }

    private static void invalid(
            List<ResourceDiagnostic> diagnostics,
            ResourceType type,
            ResourceSource source,
            Path path,
            String message
    ) {
        diagnostics.add(ResourceDiagnostic.of(
                ResourceDiagnostic.Code.INVALID_METADATA, type, source, path, message));
    }

    private static String firstLineDescription(String body) {
        for (var line : body.split("\n", -1)) {
            if (!line.isBlank()) {
                return line.length() > 60 ? line.substring(0, 60) + "..." : line;
            }
        }
        return "";
    }

    private static List<Path> sortedChildren(Path directory) throws IOException {
        var children = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            stream.forEach(children::add);
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return children;
    }

    private static boolean visible(String name) {
        return !name.startsWith(".");
    }

    private static boolean hasProjectTextResources(Path projectRoot) {
        return Files.exists(projectRoot.resolve("skills"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(projectRoot.resolve("prompts"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(projectRoot.resolve("SYSTEM.md"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(projectRoot.resolve("APPEND_SYSTEM.md"), LinkOption.NOFOLLOW_LINKS);
    }

    private static Path physical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | SecurityException failure) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static void addIgnoreLayers(
            Path directory,
            List<IgnoreLayer> layers,
            List<ResourceDiagnostic> diagnostics,
            ResourceSource source
    ) {
        for (var fileName : IGNORE_FILES) {
            Path file = directory.resolve(fileName);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            var node = new IgnoreNode();
            try (InputStream input = Files.newInputStream(file)) {
                node.parse(input);
                layers.add(new IgnoreLayer(directory, node));
            } catch (IOException | RuntimeException failure) {
                diagnostics.add(ResourceDiagnostic.of(
                        ResourceDiagnostic.Code.IO_FAILURE, ResourceType.SKILL,
                        source, file, "ignore file could not be read"));
            }
        }
    }

    private static boolean ignored(Path path, boolean directory, List<IgnoreLayer> layers) {
        IgnoreNode.MatchResult result = IgnoreNode.MatchResult.CHECK_PARENT;
        for (var layer : layers) {
            if (!path.toAbsolutePath().normalize().startsWith(layer.base().toAbsolutePath().normalize())) {
                continue;
            }
            String relative = layer.base().relativize(path).toString().replace('\\', '/');
            var candidate = layer.node().isIgnored(relative, directory);
            if (candidate != IgnoreNode.MatchResult.CHECK_PARENT) {
                result = candidate;
            }
        }
        return result == IgnoreNode.MatchResult.IGNORED;
    }

    /** Complete load inputs, including the already loaded project-instruction snapshot. */
    public record Request(
            Path workingDirectory,
            ResourceConfig config,
            ObjectMapper objectMapper,
            ProjectContextSnapshot projectContext,
            String customSystemPrompt,
            String appendSystemPrompt,
            long revision,
            boolean readToolEnabled,
            boolean bashToolEnabled
    ) {
        public Request {
            Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
            Objects.requireNonNull(config, "config must not be null");
            Objects.requireNonNull(objectMapper, "objectMapper must not be null");
            Objects.requireNonNull(projectContext, "projectContext must not be null");
        }
    }

    private record Candidate<T>(T value, Path discoveredPath, Path physicalPath) {
    }

    private record IgnoreLayer(Path base, IgnoreNode node) {
    }
}
