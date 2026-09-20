package site.pplee.jcode.codingagent;

import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.codingagent.tool.BashTool;
import site.pplee.jcode.codingagent.tool.CodingTool;
import site.pplee.jcode.codingagent.tool.CodingToolConfig;
import site.pplee.jcode.codingagent.tool.ConfiguredSearchTools;
import site.pplee.jcode.codingagent.tool.EditTool;
import site.pplee.jcode.codingagent.tool.LsTool;
import site.pplee.jcode.codingagent.tool.ReadTool;
import site.pplee.jcode.codingagent.tool.WriteTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Creates the fixed built-in tool set owned by one session. */
final class BuiltInTools {
    private BuiltInTools() {
    }

    /** Build tools in stable declaration order and reject unavailable capabilities. */
    static ToolSet create(Path workingDirectory, CodingToolConfig config) {
        validateExternalConfiguration(config);

        var tools = new ArrayList<AgentTool<?>>();
        var resources = new ArrayList<AutoCloseable>();
        try {
            ConfiguredSearchTools searchTools = null;
            if (config.enabledTools().contains(CodingTool.GREP)
                    || config.enabledTools().contains(CodingTool.FIND)) {
                searchTools = ConfiguredSearchTools.open(workingDirectory, config.search());
                resources.add(searchTools);
            }
            for (var enabled : CodingTool.values()) {
                if (!config.enabledTools().contains(enabled)) {
                    continue;
                }
                switch (enabled) {
                    case READ -> tools.add(new ReadTool(workingDirectory));
                    case WRITE -> tools.add(new WriteTool(workingDirectory));
                    case EDIT -> tools.add(new EditTool(workingDirectory));
                    case BASH -> {
                        var bash = new BashTool(workingDirectory, config.bash());
                        tools.add(bash);
                        resources.add(bash);
                    }
                    case GREP -> tools.add(searchTools.grep());
                    case FIND -> tools.add(searchTools.find());
                    case LS -> tools.add(new LsTool(workingDirectory));
                }
            }
            return new ToolSet(tools, resources);
        } catch (RuntimeException e) {
            closeReverse(resources, e);
            throw e;
        }
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
            throw new IllegalArgumentException(role + " executable is not a regular file");
        }
        if (!Files.isExecutable(executable)) {
            throw new IllegalArgumentException(role + " executable is not executable");
        }
    }

    private static void closeReverse(List<AutoCloseable> resources, Throwable primary) {
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).close();
            } catch (Exception closeFailure) {
                primary.addSuppressed(closeFailure);
            }
        }
    }

    /** Tool list plus resources whose lifetime is exactly one product session. */
    static final class ToolSet implements AutoCloseable {
        private final List<AgentTool<?>> tools;
        private final List<AutoCloseable> resources;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ToolSet(List<AgentTool<?>> tools, List<AutoCloseable> resources) {
            this.tools = List.copyOf(tools);
            this.resources = List.copyOf(resources);
        }

        List<AgentTool<?>> tools() {
            return tools;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            RuntimeException failure = null;
            for (int index = resources.size() - 1; index >= 0; index--) {
                try {
                    resources.get(index).close();
                } catch (Exception e) {
                    if (failure == null) {
                        failure = new RuntimeException("failed to close built-in tool resources", e);
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
