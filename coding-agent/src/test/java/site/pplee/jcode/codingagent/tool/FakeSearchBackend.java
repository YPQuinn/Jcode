package site.pplee.jcode.codingagent.tool;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class FakeSearchBackend implements SearchBackend {
    final List<byte[]> outputChunks = new ArrayList<>();
    ProcessRunResult processResult = ProcessRunResult.exited(0);
    StopOrigin stopOrigin = StopOrigin.NONE;
    String standardError = "";
    Path workingDirectory;
    List<String> arguments;
    boolean closed;

    @Override
    public ExecutionResult execute(
            Path workingDirectory,
            List<String> arguments,
            OutputCollector collector,
            CancellationSignal cancellation
    ) {
        this.workingDirectory = workingDirectory;
        this.arguments = List.copyOf(arguments);
        for (byte[] chunk : outputChunks) {
            if (collector.stopReason().isPresent()) {
                break;
            }
            collector.append(chunk, 0, chunk.length);
        }
        collector.finish();
        Optional<StopReason> reason = collector.stopReason();
        StopOrigin effectiveOrigin = stopOrigin;
        ProcessRunResult effectiveResult = processResult;
        if (effectiveOrigin == StopOrigin.NONE && reason.isPresent()) {
            effectiveOrigin = StopOrigin.COLLECTOR;
            effectiveResult = ProcessRunResult.cancelled();
        }
        return new ExecutionResult(
                effectiveResult, effectiveOrigin, reason, standardError);
    }

    @Override
    public void close() {
        closed = true;
    }
}
