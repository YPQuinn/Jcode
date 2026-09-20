package site.pplee.jcode.codingagent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RipgrepBackendTest {
    private static final String REQUIRED_HELP = String.join(" ",
            "--json", "--null", "--files", "--no-config", "--no-ignore-global",
            "--max-filesize", "--threads");

    @TempDir
    Path directory;

    @Test
    void capabilityProbeRequiresSupportedVersionAndOptions() {
        var executor = new ScriptedExecutor();
        executor.add(ProcessRunResult.exited(0), "ripgrep 14.1.0\n", "");
        executor.add(ProcessRunResult.exited(0), REQUIRED_HELP, "");
        var backend = backend(executor);

        backend.verifyCapabilities(directory);

        assertEquals(List.of("/configured/rg", "--version"),
                executor.requests.get(0).command());
        assertEquals(List.of("/configured/rg", "--help"),
                executor.requests.get(1).command());
        assertEquals(Map.of("LANG", "C"), executor.requests.get(0).environment());
        assertEquals(ProcessRequest.OutputMode.MERGED, executor.requests.get(0).outputMode());
    }

    @Test
    void capabilityProbeRejectsOldOrIncompleteExecutablesWithoutEchoingOutput() {
        var oldExecutor = new ScriptedExecutor();
        oldExecutor.add(ProcessRunResult.exited(0), "ripgrep 13.0.0 private\n", "");
        var oldFailure = assertThrows(IllegalArgumentException.class,
                () -> backend(oldExecutor).verifyCapabilities(directory));
        assertFalse(oldFailure.getMessage().contains("private"));

        var incomplete = new ScriptedExecutor();
        incomplete.add(ProcessRunResult.exited(0), "ripgrep 14.0.0\n", "");
        incomplete.add(ProcessRunResult.exited(0), "--json --files", "");
        var capabilityFailure = assertThrows(IllegalArgumentException.class,
                () -> backend(incomplete).verifyCapabilities(directory));
        assertTrue(capabilityFailure.getMessage().contains("required capability"));
    }

    @Test
    void collectorStopCancelsProcessAndRemainsDistinctFromCallerCancellation() {
        var executor = new ScriptedExecutor();
        executor.add(ProcessRunResult.exited(0), "record", "private diagnostic");
        executor.resultDependsOnCancellation = true;
        var backend = backend(executor);
        var collector = new StoppingCollector();

        var result = backend.execute(
                directory, List.of("--files"), collector, new MutableCancellationSignal());

        assertEquals(SearchBackend.StopOrigin.COLLECTOR, result.stopOrigin());
        assertEquals(Optional.of(SearchBackend.StopReason.RESULT_LIMIT),
                result.collectorStopReason());
        assertEquals(ProcessRunResult.Termination.CANCELLED,
                result.processResult().termination());
        assertEquals("private diagnostic", result.standardError());
        assertEquals(ProcessRequest.OutputMode.SEPARATE,
                executor.requests.getFirst().outputMode());
        assertFalse(result.toString().contains("private diagnostic"));

        var cancelledExecutor = new ScriptedExecutor();
        cancelledExecutor.add(ProcessRunResult.cancelled(), "", "");
        var cancellation = new MutableCancellationSignal();
        cancellation.cancel();
        var cancelled = backend(cancelledExecutor).execute(
                directory, List.of("--files"), new CollectingCollector(), cancellation);
        assertEquals(SearchBackend.StopOrigin.CALLER_CANCELLATION, cancelled.stopOrigin());
    }

    @Test
    void searchRequestUsesAbsoluteExecutableExplicitEnvironmentAndFixedTimeout() {
        var executor = new ScriptedExecutor();
        executor.add(ProcessRunResult.exited(0), "", "");
        var backend = backend(executor);

        backend.execute(
                directory, List.of("--json", "-e", "secret", "--", "."),
                new CollectingCollector(), new MutableCancellationSignal());

        var request = executor.requests.getFirst();
        assertEquals("/configured/rg", request.command().getFirst());
        assertEquals(RipgrepBackend.SEARCH_TIMEOUT, request.timeout());
        assertEquals(Map.of("LANG", "C"), request.environment());
        assertFalse(request.toString().contains("secret"));
        assertFalse(request.toString().contains("/configured/rg"));
    }

    private static RipgrepBackend backend(ProcessExecutor executor) {
        return new RipgrepBackend(
                new SearchConfig(Path.of("/configured/rg"), Map.of("LANG", "C")), executor);
    }

    private static class CollectingCollector implements SearchBackend.OutputCollector {
        final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        final AtomicReference<SearchBackend.StopReason> reason = new AtomicReference<>();

        @Override
        public void append(byte[] bytes, int offset, int length) {
            output.write(bytes, offset, length);
        }

        @Override
        public void finish() {
        }

        @Override
        public Optional<SearchBackend.StopReason> stopReason() {
            return Optional.ofNullable(reason.get());
        }
    }

    private static final class StoppingCollector extends CollectingCollector {
        @Override
        public void append(byte[] bytes, int offset, int length) {
            super.append(bytes, offset, length);
            reason.set(SearchBackend.StopReason.RESULT_LIMIT);
        }
    }

    private static final class ScriptedExecutor implements ProcessExecutor {
        private final ArrayDeque<Response> responses = new ArrayDeque<>();
        private final List<ProcessRequest> requests = new ArrayList<>();
        private boolean resultDependsOnCancellation;

        void add(ProcessRunResult result, String standardOutput, String standardError) {
            responses.add(new Response(
                    result,
                    standardOutput.getBytes(StandardCharsets.UTF_8),
                    standardError.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public ProcessRunResult run(
                ProcessRequest request,
                ProcessOutputConsumer output,
                CancellationSignal cancellation
        ) {
            requests.add(request);
            Response response = responses.removeFirst();
            ProcessOutputChannel stdout = request.outputMode() == ProcessRequest.OutputMode.MERGED
                    ? ProcessOutputChannel.MERGED
                    : ProcessOutputChannel.STANDARD_OUTPUT;
            if (response.standardOutput().length > 0) {
                output.accept(stdout, response.standardOutput(), 0, response.standardOutput().length);
            }
            if (response.standardError().length > 0) {
                ProcessOutputChannel stderr = request.outputMode() == ProcessRequest.OutputMode.MERGED
                        ? ProcessOutputChannel.MERGED
                        : ProcessOutputChannel.STANDARD_ERROR;
                output.accept(stderr, response.standardError(), 0, response.standardError().length);
            }
            return resultDependsOnCancellation && cancellation.isCancelled()
                    ? ProcessRunResult.cancelled()
                    : response.result();
        }

        @Override
        public void close() {
        }

        private record Response(
                ProcessRunResult result,
                byte[] standardOutput,
                byte[] standardError
        ) {
        }
    }
}
