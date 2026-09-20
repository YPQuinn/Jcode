package site.pplee.jcode.codingagent.tool;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Executes one structured search command against an explicitly configured backend. */
interface SearchBackend extends AutoCloseable {
    ExecutionResult execute(
            Path workingDirectory,
            List<String> arguments,
            OutputCollector collector,
            CancellationSignal cancellation
    );

    @Override
    void close();

    /** Incremental, bounded consumer for backend standard output. */
    interface OutputCollector {
        void append(byte[] bytes, int offset, int length);

        void finish();

        Optional<StopReason> stopReason();
    }

    enum StopReason {
        RESULT_LIMIT,
        SCAN_LIMIT,
        PARSE_FAILURE
    }

    enum StopOrigin {
        NONE,
        CALLER_CANCELLATION,
        COLLECTOR
    }

    record ExecutionResult(
            ProcessRunResult processResult,
            StopOrigin stopOrigin,
            Optional<StopReason> collectorStopReason,
            String standardError
    ) {
        public ExecutionResult {
            processResult = Objects.requireNonNull(processResult, "processResult must not be null");
            stopOrigin = Objects.requireNonNull(stopOrigin, "stopOrigin must not be null");
            collectorStopReason = Objects.requireNonNull(
                    collectorStopReason, "collectorStopReason must not be null");
            standardError = Objects.requireNonNull(standardError, "standardError must not be null");
            if (stopOrigin == StopOrigin.COLLECTOR && collectorStopReason.isEmpty()) {
                throw new IllegalArgumentException("collector stop must include a reason");
            }
        }

        @Override
        public String toString() {
            return "ExecutionResult[processResult=" + processResult
                    + ", stopOrigin=" + stopOrigin
                    + ", collectorStopReason=" + collectorStopReason
                    + ", standardError=redacted]";
        }
    }
}
