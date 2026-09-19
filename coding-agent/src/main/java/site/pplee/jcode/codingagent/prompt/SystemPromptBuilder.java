package site.pplee.jcode.codingagent.prompt;

import site.pplee.jcode.ai.tool.ToolSpec;

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
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        var toolSnapshot = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
        if (toolSnapshot.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("tools must not contain null");
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
        prompt.append("\n\nCurrent working directory: ")
                .append(workingDirectory.toAbsolutePath().normalize());
        return prompt.toString();
    }
}
