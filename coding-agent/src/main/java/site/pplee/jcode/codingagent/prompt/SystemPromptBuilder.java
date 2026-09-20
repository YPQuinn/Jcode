package site.pplee.jcode.codingagent.prompt;

import site.pplee.jcode.ai.tool.ToolSpec;
import site.pplee.jcode.codingagent.context.ProjectContextDiagnostic;
import site.pplee.jcode.codingagent.context.ProjectContextFile;
import site.pplee.jcode.codingagent.context.ProjectContextLoadException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Pure builder for the first-stage coding system prompt. */
public final class SystemPromptBuilder {
    private static final int MAX_PROJECT_CONTEXT_BYTES = 2 * 1024 * 1024;
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
            var contextBlock = new StringBuilder("<project_context>\n")
                    .append("Project-specific instructions and guidelines:\n\n");
            for (var file : contextSnapshot) {
                requireXmlText(file.content(), file.discoveredPath());
                requireXmlText(file.discoveredPath().toString(), file.discoveredPath());
                contextBlock.append("<project_instructions path=\"")
                        .append(escapeAttribute(file.discoveredPath().toString()))
                        .append("\">\n")
                        .append(escapeText(file.content()))
                        .append("\n</project_instructions>\n");
            }
            contextBlock.append("</project_context>");
            if (contextBlock.toString().getBytes(StandardCharsets.UTF_8).length > MAX_PROJECT_CONTEXT_BYTES) {
                throw renderingFailure(ProjectContextDiagnostic.Code.LOAD_LIMIT_EXCEEDED, null);
            }
            prompt.append("\n\n").append(contextBlock);
        }
        return prompt.append("\n\nCurrent working directory: ")
                .append(workingDirectory.toAbsolutePath().normalize())
                .toString();
    }

    private static String escapeText(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\r", "&#13;");
    }

    private static String escapeAttribute(String value) {
        return escapeText(value)
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
                .replace("\t", "&#9;")
                .replace("\n", "&#10;");
    }

    private static void requireXmlText(String value, Path source) {
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            boolean valid = codePoint == '\t' || codePoint == '\n' || codePoint == '\r'
                    || codePoint >= 0x20 && codePoint <= 0xd7ff
                    || codePoint >= 0xe000 && codePoint <= 0xfffd
                    || codePoint >= 0x10000 && codePoint <= 0x10ffff;
            if (!valid || (codePoint & 0xffff) == 0xfffe || (codePoint & 0xffff) == 0xffff) {
                throw renderingFailure(ProjectContextDiagnostic.Code.UNSAFE_CONTENT, source);
            }
            index += Character.charCount(codePoint);
        }
    }

    private static ProjectContextLoadException renderingFailure(
            ProjectContextDiagnostic.Code code,
            Path source
    ) {
        return new ProjectContextLoadException(List.of(new ProjectContextDiagnostic(
                code, ProjectContextDiagnostic.Severity.ERROR, source, null)));
    }
}
