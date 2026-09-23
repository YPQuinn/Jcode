package site.pplee.jcode.codingagent.prompt;

import site.pplee.jcode.ai.tool.ToolSpec;
import site.pplee.jcode.codingagent.context.ProjectContextFile;
import site.pplee.jcode.codingagent.resource.ResourceSnapshot;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Pure builder for the first-stage coding system prompt. */
public final class SystemPromptBuilder {
    private static final String DEFAULT_BODY = """
            You are an expert coding assistant operating inside Jcode.

            Guidelines:
            - Be concise and accurate.
            - Show file paths clearly when working with files.
            - Inspect relevant files before proposing changes.
            """.stripTrailing();

    private SystemPromptBuilder() {
    }

    /** Build a prompt from explicit values without performing I/O or discovery. */
    public static String build(
            Path workingDirectory,
            List<ToolSpec> tools,
            String customPrompt,
            String appendPrompt
    ) {
        return build(workingDirectory, tools, customPrompt, appendPrompt, List.of());
    }

    /** Build a prompt with preloaded effective project instruction sources. */
    public static String build(
            Path workingDirectory,
            List<ToolSpec> tools,
            String customPrompt,
            String appendPrompt,
            List<ProjectContextFile> contextFiles
    ) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        var toolSnapshot = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
        if (toolSnapshot.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("tools must not contain null");
        }
        var contextSnapshot = List.copyOf(Objects.requireNonNull(contextFiles, "contextFiles must not be null"));
        if (contextSnapshot.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("contextFiles must not contain null");
        }
        var names = new HashSet<String>();
        for (var tool : toolSnapshot) {
            if (!names.add(tool.name())) {
                throw new IllegalArgumentException("duplicate tool name: " + tool.name());
            }
        }

        var prompt = new StringBuilder(customPrompt == null ? DEFAULT_BODY : customPrompt);
        if (appendPrompt != null) {
            prompt.append("\n\n").append(appendPrompt);
        }
        prompt.append("\n\nAvailable tools:\n");
        if (toolSnapshot.isEmpty()) {
            prompt.append("(none)");
        } else {
            for (int index = 0; index < toolSnapshot.size(); index++) {
                var tool = toolSnapshot.get(index);
                if (index > 0) {
                    prompt.append('\n');
                }
                prompt.append("- ").append(tool.name()).append(": ").append(tool.description());
            }
        }
        if (!contextSnapshot.isEmpty()) {
            prompt.append("\n\n<project_context>\n")
                    .append("Project-specific instructions and guidelines:\n\n");
            for (var file : contextSnapshot) {
                prompt.append("<project_instructions path=\"")
                        .append(escapeAttribute(file.discoveredPath().toString()))
                        .append("\">\n")
                        .append(file.content())
                        .append("\n</project_instructions>\n");
            }
            prompt.append("</project_context>");
        }
        return prompt.append("\n\nCurrent working directory: ")
                .append(workingDirectory.toAbsolutePath().normalize())
                .toString();
    }

    /** Build from one combined resource snapshot and the actual built-in reading capabilities. */
    public static String build(
            Path workingDirectory,
            List<ToolSpec> tools,
            ResourceSnapshot resources,
            boolean readToolEnabled,
            boolean bashToolEnabled
    ) {
        Objects.requireNonNull(resources, "resources must not be null");
        String prompt = build(
                workingDirectory,
                tools,
                resources.systemPrompt().orElse(null),
                resources.appendSystemPrompt().orElse(null),
                resources.projectContext().files());
        var visible = resources.skills().stream()
                .filter(skill -> !skill.disableModelInvocation())
                .toList();
        if (visible.isEmpty() || (!readToolEnabled && !bashToolEnabled)) {
            return prompt;
        }
        var catalog = new StringBuilder("\n\n<available_skills>\n");
        catalog.append(readToolEnabled
                ? "Use the built-in read tool to load a skill body before following it.\n"
                : "Use the built-in bash tool to read a skill body before following it.\n");
        catalog.append("Resolve referenced relative paths from the skill base directory.\n");
        for (var skill : visible) {
            catalog.append("<skill name=\"").append(escapeAttribute(skill.name()))
                    .append("\" description=\"").append(escapeAttribute(skill.description()))
                    .append("\" location=\"").append(escapeAttribute(skill.filePath().toString()))
                    .append("\" base_dir=\"").append(escapeAttribute(skill.baseDirectory().toString()))
                    .append("\"/>\n");
        }
        catalog.append("</available_skills>");
        int cwd = prompt.lastIndexOf("\n\nCurrent working directory:");
        return cwd < 0 ? prompt + catalog : prompt.substring(0, cwd) + catalog + prompt.substring(cwd);
    }

    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
                .replace("\t", "&#9;")
                .replace("\n", "&#10;")
                .replace("\r", "&#13;");
    }
}
