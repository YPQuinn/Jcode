package site.pplee.jcode.codingagent.resource;

import site.pplee.jcode.codingagent.context.ProjectContextSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable combined view of project instructions and explicitly enabled text resources. */
public final class ResourceSnapshot {
    private final long revision;
    private final Map<String, SkillResource> skills;
    private final Map<String, PromptTemplateResource> templates;
    private final String systemPrompt;
    private final String appendSystemPrompt;
    private final ProjectContextSnapshot projectContext;
    private final List<ResourceDiagnostic> diagnostics;

    public ResourceSnapshot(
            long revision,
            List<SkillResource> skills,
            List<PromptTemplateResource> templates,
            String systemPrompt,
            String appendSystemPrompt,
            ProjectContextSnapshot projectContext,
            List<ResourceDiagnostic> diagnostics
    ) {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        this.revision = revision;
        this.skills = indexed(skills, SkillResource::name, "skill");
        this.templates = indexed(templates, PromptTemplateResource::name, "template");
        this.systemPrompt = systemPrompt;
        this.appendSystemPrompt = appendSystemPrompt;
        this.projectContext = Objects.requireNonNull(projectContext, "projectContext must not be null");
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }

    public static ResourceSnapshot disabled(ProjectContextSnapshot context) {
        return new ResourceSnapshot(0, List.of(), List.of(), null, null, context, List.of());
    }

    public long revision() {
        return revision;
    }

    public List<SkillResource> skills() {
        return List.copyOf(skills.values());
    }

    public Optional<SkillResource> skill(String name) {
        return Optional.ofNullable(skills.get(Objects.requireNonNull(name, "name must not be null")));
    }

    public List<PromptTemplateResource> templates() {
        return List.copyOf(templates.values());
    }

    public Optional<PromptTemplateResource> template(String name) {
        return Optional.ofNullable(templates.get(Objects.requireNonNull(name, "name must not be null")));
    }

    public Optional<String> systemPrompt() {
        return Optional.ofNullable(systemPrompt);
    }

    public Optional<String> appendSystemPrompt() {
        return Optional.ofNullable(appendSystemPrompt);
    }

    public ProjectContextSnapshot projectContext() {
        return projectContext;
    }

    public List<ResourceDiagnostic> diagnostics() {
        return diagnostics;
    }

    private static <T> Map<String, T> indexed(
            List<T> values,
            java.util.function.Function<T, String> name,
            String kind
    ) {
        Objects.requireNonNull(values, kind + "s must not be null");
        var result = new LinkedHashMap<String, T>();
        for (var value : values) {
            Objects.requireNonNull(value, kind + "s must not contain null");
            if (result.putIfAbsent(name.apply(value), value) != null) {
                throw new IllegalArgumentException("duplicate " + kind + " name");
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
