package site.pplee.jcode.codingagent;

import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.codingagent.tool.CodingTool;
import site.pplee.jcode.codingagent.tool.CodingToolConfig;
import site.pplee.jcode.codingagent.tool.ReadTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Creates the fixed built-in tool set owned by one session. */
final class BuiltInTools {
    private BuiltInTools() {
    }

    /** Build tools in stable declaration order and reject unavailable capabilities. */
    static List<AgentTool<?>> create(Path workingDirectory, CodingToolConfig config) {
        validateExternalConfiguration(config);

        var tools = new ArrayList<AgentTool<?>>();
        for (var enabled : CodingTool.values()) {
            if (!config.enabledTools().contains(enabled)) {
                continue;
            }
            switch (enabled) {
                case READ -> tools.add(new ReadTool(workingDirectory));
                case WRITE, EDIT, BASH, GREP, FIND, LS -> throw new IllegalArgumentException(
                        "built-in tool is not implemented yet: " + enabled.toolName());
            }
        }
        return List.copyOf(tools);
    }

    private static void validateExternalConfiguration(CodingToolConfig config) {
        if (config.enabledTools().contains(CodingTool.BASH)) {
            if (config.bash() == null) {
                throw new IllegalArgumentException(
                        "bash configuration is required when bash is enabled");
            }
            validateExecutable(config.bash().executable(), "bash");
        }
        if (config.enabledTools().contains(CodingTool.GREP)
                || config.enabledTools().contains(CodingTool.FIND)) {
            if (config.search() == null) {
                throw new IllegalArgumentException(
                        "search configuration is required when grep or find is enabled");
            }
            validateExecutable(config.search().executable(), "search");
        }
    }

    private static void validateExecutable(Path executable, String role) {
        if (!Files.isRegularFile(executable)) {
            throw new IllegalArgumentException(role + " executable is not a regular file: " + executable);
        }
        if (!Files.isExecutable(executable)) {
            throw new IllegalArgumentException(role + " executable is not executable: " + executable);
        }
    }
}
