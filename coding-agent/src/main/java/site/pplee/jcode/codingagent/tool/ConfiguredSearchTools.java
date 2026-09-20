package site.pplee.jcode.codingagent.tool;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Session-owned grep/find pair sharing one process runner. */
public final class ConfiguredSearchTools implements AutoCloseable {
    private final ProcessRunner runner;
    private final GrepTool grep;
    private final FindTool find;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ConfiguredSearchTools(
            ProcessRunner runner,
            GrepTool grep,
            FindTool find
    ) {
        this.runner = runner;
        this.grep = grep;
        this.find = find;
    }

    /** Use the configured executables and create tools sharing one process budget. */
    public static ConfiguredSearchTools open(Path workingDirectory, SearchConfig config) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Objects.requireNonNull(config, "config must not be null");
        var runner = new ProcessRunner();
        try {
            return new ConfiguredSearchTools(
                    runner,
                    new GrepTool(workingDirectory, new SearchProcessBackend(
                            config.requireGrepExecutable(), config.environment(), runner)),
                    new FindTool(workingDirectory, new SearchProcessBackend(
                            config.requireFindExecutable(), config.environment(), runner)));
        } catch (RuntimeException e) {
            runner.close();
            throw e;
        }
    }

    /** Return the grep tool bound to this shared backend. */
    public GrepTool grep() {
        return grep;
    }

    /** Return the find tool bound to this shared backend. */
    public FindTool find() {
        return find;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            runner.close();
        }
    }
}
