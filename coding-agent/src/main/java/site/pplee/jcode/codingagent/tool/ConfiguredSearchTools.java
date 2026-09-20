package site.pplee.jcode.codingagent.tool;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Session-owned grep/find pair sharing one verified ripgrep backend. */
public final class ConfiguredSearchTools implements AutoCloseable {
    private final RipgrepBackend backend;
    private final GrepTool grep;
    private final FindTool find;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ConfiguredSearchTools(
            RipgrepBackend backend,
            GrepTool grep,
            FindTool find
    ) {
        this.backend = backend;
        this.grep = grep;
        this.find = find;
    }

    /** Verify the configured executable and create tools sharing one process budget. */
    public static ConfiguredSearchTools open(Path workingDirectory, SearchConfig config) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Objects.requireNonNull(config, "config must not be null");
        var backend = RipgrepBackend.open(workingDirectory, config);
        try {
            return new ConfiguredSearchTools(
                    backend,
                    new GrepTool(workingDirectory, backend),
                    new FindTool(workingDirectory, backend));
        } catch (RuntimeException e) {
            backend.close();
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
            backend.close();
        }
    }
}
