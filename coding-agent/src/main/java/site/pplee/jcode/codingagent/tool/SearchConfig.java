package site.pplee.jcode.codingagent.tool;

import java.nio.file.Path;
import java.util.Map;

/** Explicit process configuration reserved for external search backends. */
public record SearchConfig(
        Path executable,
        Map<String, String> environment
) {
    public SearchConfig {
        executable = BashConfig.absolutePath(executable, "executable");
        environment = BashConfig.environmentSnapshot(environment);
    }

    @Override
    public String toString() {
        return "SearchConfig[executable=" + executable + ", environment=redacted]";
    }
}
