package site.pplee.jcode.codingagent.tool;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** {@link ProcessBuilder}-backed local process launcher. */
final class LocalProcessLauncher implements ProcessLauncher {
    @Override
    public ManagedProcess start(ProcessRequest request) throws IOException {
        var builder = new ProcessBuilder(request.command());
        builder.directory(request.workingDirectory().toFile());
        builder.redirectErrorStream(request.outputMode() == ProcessRequest.OutputMode.MERGED);
        if (request.environment() != null) {
            builder.environment().clear();
            builder.environment().putAll(request.environment());
        }
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            return new LocalManagedProcess(process);
        } catch (IOException e) {
            process.destroyForcibly();
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            throw e;
        }
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // Preserve the original launch-cleanup failure.
        }
    }

    private static final class LocalManagedProcess implements ManagedProcess {
        private final Process process;
        private final Set<ProcessHandle> observedProcesses = new LinkedHashSet<>();

        private LocalManagedProcess(Process process) {
            this.process = process;
            observedProcesses.add(process.toHandle());
        }

        @Override
        public InputStream standardOutput() {
            return process.getInputStream();
        }

        @Override
        public InputStream standardError() {
            return process.getErrorStream();
        }

        @Override
        public CompletionStage<Integer> exitCode() {
            return process.onExit().thenApply(ignored -> process.exitValue());
        }

        @Override
        public synchronized CompletionStage<Void> terminateTree(boolean force) {
            // Retain handles across phases: a child can outlive its parent and be reparented.
            for (ProcessHandle observed : List.copyOf(observedProcesses)) {
                if (observed.isAlive()) {
                    observed.descendants().forEach(observedProcesses::add);
                }
            }
            var ordered = new ArrayList<>(observedProcesses);
            ordered.sort(Comparator.comparingInt(LocalManagedProcess::depth).reversed());
            var exits = ordered.stream().map(ProcessHandle::onExit)
                    .toArray(CompletableFuture<?>[]::new);
            for (ProcessHandle handle : ordered) {
                terminate(handle, force);
            }
            return CompletableFuture.allOf(exits);
        }

        @Override
        public void closeOutput() throws IOException {
            IOException failure = null;
            try {
                process.getInputStream().close();
            } catch (IOException e) {
                failure = e;
            }
            try {
                process.getErrorStream().close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static int depth(ProcessHandle handle) {
            int depth = 0;
            var parent = handle.parent();
            while (parent.isPresent()) {
                depth++;
                parent = parent.orElseThrow().parent();
            }
            return depth;
        }

        private static void terminate(ProcessHandle handle, boolean force) {
            if (!handle.isAlive()) {
                return;
            }
            if (force) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        }
    }
}
