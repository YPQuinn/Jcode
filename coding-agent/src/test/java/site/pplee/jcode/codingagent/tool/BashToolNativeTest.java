package site.pplee.jcode.codingagent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;
import site.pplee.jcode.codingagent.support.NativeToolTestSupport;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BashToolNativeTest {
    @TempDir
    Path directory;

    @Test
    void runsRealBashWithMergedOutputEnvironmentAndExitStatus() {
        var config = config(Map.of("VALUE", "configured"));

        try (var tool = new BashTool(directory, config)) {
            var result = tool.execute(
                            "call",
                            new BashToolArguments(
                                    "printf 'out:%s\\n' \"$VALUE\"; printf 'err\\n' >&2; exit 7",
                                    5L),
                            ToolUpdateSink.noop(),
                            new MutableCancellationSignal())
                    .toCompletableFuture().join();

            assertTrue(result.error());
            String text = ToolTestSupport.text(result);
            assertTrue(text.contains("out:configured\n"));
            assertTrue(text.contains("err\n"));
            assertTrue(text.endsWith("[Process exited with code 7]"));
        }
    }

    @Test
    void timeoutKillsTheProcessWhileSlowUpdateDeliveryIsStillPending() throws Exception {
        Path pidFile = directory.resolve("pid.txt");
        var updateEntered = new CompletableFuture<Void>();
        var releaseUpdate = new CompletableFuture<Void>();
        ToolUpdateSink sink = update -> {
            updateEntered.complete(null);
            return releaseUpdate;
        };

        try (var tool = new BashTool(directory, config(Map.of()));
             var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var execution = CompletableFuture.supplyAsync(() -> tool.execute(
                            "call",
                            new BashToolArguments(
                                    "printf '%s' \"$$\" > pid.txt; printf 'started\\n'; "
                                            + "exec /bin/sleep 30",
                                    1L),
                            sink,
                            new MutableCancellationSignal())
                    .toCompletableFuture().join(), caller);

            updateEntered.get(5, TimeUnit.SECONDS);
            long pid = awaitPid(pidFile);
            ProcessHandle handle = ProcessHandle.of(pid).orElseThrow();
            handle.onExit().get(5, TimeUnit.SECONDS);
            assertFalse(handle.isAlive());
            assertFalse(execution.isDone(), "slow sink should delay settlement, not process timeout");

            releaseUpdate.complete(null);
            var result = execution.get(5, TimeUnit.SECONDS);
            assertTrue(result.error());
            assertTrue(ToolTestSupport.text(result).contains("Process timed out after 1 seconds"));
        }
    }

    @Test
    void cancellationKillsTheRealForegroundProcess() throws Exception {
        Path pidFile = directory.resolve("pid.txt");
        var cancellation = new MutableCancellationSignal();

        try (var tool = new BashTool(directory, config(Map.of()));
             var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var execution = CompletableFuture.supplyAsync(() -> tool.execute(
                            "call",
                            new BashToolArguments(
                                    "printf '%s' \"$$\" > pid.txt; exec /bin/sleep 30",
                                    20L),
                            ToolUpdateSink.noop(),
                            cancellation)
                    .toCompletableFuture().join(), caller);

            long pid = awaitPid(pidFile);
            ProcessHandle handle = ProcessHandle.of(pid).orElseThrow();
            cancellation.cancel();

            var result = execution.get(5, TimeUnit.SECONDS);
            handle.onExit().get(5, TimeUnit.SECONDS);
            assertFalse(handle.isAlive());
            assertTrue(result.error());
            assertTrue(ToolTestSupport.text(result).contains("Process cancelled"));
        }
    }

    @Test
    void failedSinkStopsTheRealProcessBeforePropagatingTheOriginalFailure() throws Exception {
        Path pidFile = directory.resolve("pid.txt");
        var updateEntered = new CompletableFuture<Void>();
        var releaseUpdate = new CompletableFuture<Void>();
        var sinkFailure = new IllegalStateException("intentional sink failure");
        var cancellation = new MutableCancellationSignal();

        try (var tool = new BashTool(directory, config(Map.of()));
             var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var execution = CompletableFuture.supplyAsync(() -> tool.execute(
                            "call", new BashToolArguments(
                                    "printf '%s' \"$$\" > pid.txt; printf 'started\\n'; "
                                            + "exec /bin/sleep 30", 20L),
                            update -> {
                                updateEntered.complete(null);
                                return releaseUpdate;
                            }, cancellation)
                    .toCompletableFuture().join(), caller);
            try {
                updateEntered.get(5, TimeUnit.SECONDS);
                long pid = awaitPid(pidFile);
                ProcessHandle handle = ProcessHandle.of(pid).orElseThrow();
                assertTrue(handle.isAlive());
                releaseUpdate.completeExceptionally(sinkFailure);

                var failure = assertThrows(ExecutionException.class,
                        () -> execution.get(5, TimeUnit.SECONDS));
                assertSame(sinkFailure, failure.getCause());
                assertFalse(handle.isAlive(), "cleanup must precede exceptional completion");
                assertFalse(cancellation.isCancelled());
            } finally {
                releaseUpdate.complete(null);
                killRecordedProcess(pidFile);
            }
        }
    }

    @Test
    void timeoutAlsoReapsForegroundChildrenThatIgnoreTerm() throws Exception {
        Path childPid = directory.resolve("child.pid");
        try (var tool = new BashTool(directory, config(Map.of()))) {
            try {
                var result = tool.execute("call", new BashToolArguments(
                                "\"$BASH\" --noprofile --norc -c 'trap \"\" TERM; "
                                        + "printf \"%s\" \"$$\" > child.pid; "
                                        + "while :; do :; done' >/dev/null 2>&1; printf finished",
                                1L),
                        ToolUpdateSink.noop(), new MutableCancellationSignal())
                        .toCompletableFuture().join();
                assertTrue(result.error());
                assertTrue(ToolTestSupport.text(result).contains("Process timed out"));
                long pid = Long.parseLong(Files.readString(childPid));
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                        "returning from timeout must not abandon a known foreground child");
            } finally {
                killRecordedProcess(childPid);
            }
        }
    }

    private static void killRecordedProcess(Path pidFile) throws Exception {
        if (Files.isRegularFile(pidFile) && Files.size(pidFile) > 0) {
            long pid = Long.parseLong(Files.readString(pidFile));
            var handle = ProcessHandle.of(pid);
            if (handle.isPresent()) {
                var process = handle.orElseThrow();
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.onExit().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void replacesInvalidBytesAndBoundsOneHugeOutputLine() {
        String command = "printf '\\377'; i=0; while ((i < 70000)); do printf x; ((i++)); done";

        try (var tool = new BashTool(directory, config(Map.of()))) {
            var result = tool.execute(
                            "call", new BashToolArguments(command, 10L),
                            ToolUpdateSink.noop(), new MutableCancellationSignal())
                    .toCompletableFuture().join();
            String text = ToolTestSupport.text(result);

            assertFalse(result.error());
            assertTrue(text.contains("Invalid UTF-8 bytes were replaced"));
            assertTrue(text.contains("Output truncated"));
            assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    <= BashTool.MAX_OUTPUT_BYTES + BashTool.MAX_STATUS_BYTES);
        }
    }

    private BashConfig config(Map<String, String> environment) {
        NativeToolTestSupport.requirePosixProcessSupport();
        return new BashConfig(NativeToolTestSupport.requireBash(), environment,
                Duration.ofSeconds(5), Duration.ofSeconds(20));
    }

    private long awaitPid(Path pidFile) throws Exception {
        if (Files.isRegularFile(pidFile) && Files.size(pidFile) > 0) {
            return Long.parseLong(Files.readString(pidFile));
        }
        try (var watch = FileSystems.getDefault().newWatchService()) {
            directory.register(watch,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    fail("process did not write its pid");
                }
                var key = watch.poll(remaining, TimeUnit.NANOSECONDS);
                if (key == null) {
                    fail("process did not write its pid");
                }
                key.pollEvents();
                key.reset();
                if (Files.isRegularFile(pidFile) && Files.size(pidFile) > 0) {
                    return Long.parseLong(Files.readString(pidFile));
                }
            }
        }
    }
}
