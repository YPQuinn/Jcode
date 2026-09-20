package site.pplee.jcode.codingagent.tool;

import site.pplee.jcode.ai.concurrent.CancellationRegistration;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit, capability-checked ripgrep process backend shared by search tools. */
final class RipgrepBackend implements SearchBackend {
    static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(30);
    static final int MINIMUM_MAJOR_VERSION = 14;

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final List<String> REQUIRED_HELP_OPTIONS = List.of(
            "--json",
            "--null",
            "--files",
            "--no-config",
            "--no-ignore-global",
            "--max-filesize",
            "--threads");
    private static final CancellationSignal NEVER_CANCELLED = new CancellationSignal() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void throwIfCancelled() {
        }

        @Override
        public CancellationRegistration onCancellation(Runnable listener) {
            Objects.requireNonNull(listener, "listener must not be null");
            return () -> { };
        }
    };

    private final SearchConfig config;
    private final ProcessExecutor executor;

    static RipgrepBackend open(Path probeDirectory, SearchConfig config) {
        var executor = new ProcessRunner();
        try {
            var backend = new RipgrepBackend(config, executor);
            backend.verifyCapabilities(probeDirectory);
            return backend;
        } catch (RuntimeException e) {
            executor.close();
            throw e;
        }
    }

    RipgrepBackend(SearchConfig config, ProcessExecutor executor) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    void verifyCapabilities(Path probeDirectory) {
        Objects.requireNonNull(probeDirectory, "probeDirectory must not be null");
        String version = probe(probeDirectory, List.of("--version"), 8 * 1_024);
        int majorVersion = parseMajorVersion(version);
        if (majorVersion < MINIMUM_MAJOR_VERSION) {
            throw new IllegalArgumentException(
                    "search executable must be ripgrep " + MINIMUM_MAJOR_VERSION + " or newer");
        }
        String help = probe(probeDirectory, List.of("--help"), 512 * 1_024);
        for (String option : REQUIRED_HELP_OPTIONS) {
            if (!help.contains(option)) {
                throw new IllegalArgumentException(
                        "search executable does not provide a required capability: " + option);
            }
        }
    }

    @Override
    public ExecutionResult execute(
            Path workingDirectory,
            List<String> arguments,
            OutputCollector collector,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(collector, "collector must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        var command = new ArrayList<String>(arguments.size() + 1);
        command.add(config.executable().toString());
        command.addAll(arguments);
        var request = new ProcessRequest(
                command,
                workingDirectory,
                config.environment(),
                SEARCH_TIMEOUT,
                ProcessRequest.OutputMode.SEPARATE);
        var internalCancellation = new CancellationSource();
        var stopOrigin = new AtomicReference<>(StopOrigin.NONE);
        var standardError = new ProcessOutputBuffer(4, 1_024);

        ProcessRunResult processResult;
        try (var ignored = cancellation.onCancellation(() -> {
            if (stopOrigin.compareAndSet(StopOrigin.NONE, StopOrigin.CALLER_CANCELLATION)) {
                internalCancellation.cancel();
            }
        })) {
            processResult = executor.run(request, (channel, bytes, offset, length) -> {
                if (channel == ProcessOutputChannel.STANDARD_ERROR) {
                    standardError.append(bytes, offset, length);
                    return;
                }
                collector.append(bytes, offset, length);
                if (collector.stopReason().isPresent()
                        && stopOrigin.compareAndSet(StopOrigin.NONE, StopOrigin.COLLECTOR)) {
                    internalCancellation.cancel();
                }
            }, internalCancellation.signal());
        }
        collector.finish();
        standardError.finish();
        Optional<StopReason> collectorReason = collector.stopReason();
        StopOrigin origin = stopOrigin.get();
        if (origin == StopOrigin.COLLECTOR && collectorReason.isEmpty()) {
            origin = StopOrigin.NONE;
        }
        return new ExecutionResult(
                processResult,
                origin,
                collectorReason,
                standardError.snapshot().content());
    }

    @Override
    public void close() {
        executor.close();
    }

    private String probe(Path directory, List<String> arguments, int maximumBytes) {
        var command = new ArrayList<String>(arguments.size() + 1);
        command.add(config.executable().toString());
        command.addAll(arguments);
        var output = new ProcessOutputBuffer(10_000, maximumBytes);
        var request = new ProcessRequest(
                command,
                directory,
                config.environment(),
                PROBE_TIMEOUT,
                ProcessRequest.OutputMode.MERGED);
        ProcessRunResult result = executor.run(request,
                (channel, bytes, offset, length) -> output.append(bytes, offset, length),
                NEVER_CANCELLED);
        output.finish();
        if (result.termination() != ProcessRunResult.Termination.EXITED
                || result.exitCode() != 0
                || output.snapshot().truncated()) {
            throw new IllegalArgumentException("search executable capability probe failed");
        }
        return output.snapshot().content();
    }

    private static int parseMajorVersion(String versionOutput) {
        int lineEnd = versionOutput.indexOf('\n');
        String firstLine = lineEnd < 0 ? versionOutput : versionOutput.substring(0, lineEnd);
        String prefix = "ripgrep ";
        if (!firstLine.startsWith(prefix)) {
            throw new IllegalArgumentException("search executable is not a supported ripgrep binary");
        }
        int start = prefix.length();
        int end = start;
        while (end < firstLine.length() && Character.isDigit(firstLine.charAt(end))) {
            end++;
        }
        if (end == start) {
            throw new IllegalArgumentException("search executable version could not be determined");
        }
        try {
            return Integer.parseInt(firstLine.substring(start, end));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("search executable version could not be determined", e);
        }
    }
}
