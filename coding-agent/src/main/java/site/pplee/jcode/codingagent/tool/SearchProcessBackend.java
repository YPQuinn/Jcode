package site.pplee.jcode.codingagent.tool;

import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Executes an explicitly configured search binary with bounded output collectors. */
final class SearchProcessBackend implements SearchBackend {
    static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(30);
    private final Path executable;
    private final Map<String, String> environment;
    private final ProcessExecutor executor;

    static SearchProcessBackend open(Path workingDirectory, SearchConfig config) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        return new SearchProcessBackend(config.requireGrepExecutable(), config.environment(), new ProcessRunner());
    }

    SearchProcessBackend(Path executable, Map<String, String> environment, ProcessExecutor executor) {
        this.executable = Objects.requireNonNull(executable, "executable must not be null");
        this.environment = environment == null ? null : BashConfig.environmentSnapshot(environment);
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
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
        command.add(executable.toString());
        command.addAll(arguments);
        var request = new ProcessRequest(
                command,
                workingDirectory,
                environment,
                SEARCH_TIMEOUT,
                ProcessRequest.OutputMode.SEPARATE);
        var internalCancellation = new CancellationSource();
        var stopOrigin = new AtomicReference<>(StopOrigin.NONE);
        var standardError = new ProcessOutputBuffer(64, 8 * 1_024);

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

}
