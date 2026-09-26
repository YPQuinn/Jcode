package site.pplee.jcode.app;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.codingagent.CodingAgentRunResult;
import site.pplee.jcode.codingagent.CodingAgentSession;
import site.pplee.jcode.codingagent.InputRecord;
import site.pplee.jcode.codingagent.InputRequest;
import site.pplee.jcode.protocol.ErrorCode;
import site.pplee.jcode.protocol.InputCommand;
import site.pplee.jcode.protocol.InputView;
import site.pplee.jcode.protocol.RunCommand;
import site.pplee.jcode.protocol.RunKind;
import site.pplee.jcode.protocol.RunStatus;
import site.pplee.jcode.protocol.RunView;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** In-process owner of one strict Session's command identities and run results. */
public final class ManagedSession {
    /** Maximum retained command receipts per managed session. Terminal receipts expire oldest first. */
    public static final int MAX_RETAINED_COMMANDS = 1_024;
    private static final int MAX_RESULT_TEXT_CHARS = 16_384;

    private final Object lock = new Object();
    private final CodingAgentSession core;
    private final String sessionId;
    private final Map<CommandKey, CommandEntry> commands = new LinkedHashMap<>();
    private final Map<String, RunEntry> runs = new LinkedHashMap<>();
    private final Map<String, InputEntry> inputs = new LinkedHashMap<>();
    private RunEntry activeRun;
    private int pendingInputAdmissions;
    private CompletableFuture<Void> closing;
    private boolean closed;

    ManagedSession(CodingAgentSession core) {
        this.core = Objects.requireNonNull(core, "core must not be null");
        this.sessionId = core.history().header().id().toString();
    }

    public String sessionId() {
        return sessionId;
    }

    public Path workingDirectory() {
        return core.workingDirectory();
    }

    public Optional<Path> sessionFile() {
        return core.sessionFile();
    }

    public Optional<String> currentLeafId() {
        return core.history().currentEntryId();
    }

    /** Submit a run command; an accepted command remains queryable even if startup fails. */
    public RunView start(RunCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        RunEntry entry;
        synchronized (lock) {
            var key = new CommandKey(Operation.RUN, command.commandId());
            var previous = commands.get(key);
            if (previous != null) {
                var existing = (RunEntry) previous;
                if (!existing.command.equals(command)) {
                    throw error(ErrorCode.IDEMPOTENCY_CONFLICT, "commandId belongs to a different run request");
                }
                return existing.view;
            }
            requireOpen();
            if (runs.containsKey(command.runId())) {
                throw error(ErrorCode.IDEMPOTENCY_CONFLICT, "runId belongs to another command");
            }
            if (activeRun != null) {
                throw error(ErrorCode.SESSION_BUSY, "session already has an active run");
            }
            String actualLeaf = core.history().currentEntryId().orElse(null);
            if (command.expectedLeafId() != null
                    && !command.expectedLeafId().equals(actualLeaf)) {
                throw error(ErrorCode.STATE_CONFLICT, "session leaf has changed");
            }
            makeRoom();
            entry = new RunEntry(command, new RunView(
                    sessionId, command.commandId(), command.runId(), RunStatus.ACCEPTED,
                    false, null, null, false, null));
            commands.put(key, entry);
            runs.put(command.runId(), entry);
            activeRun = entry;
        }

        CompletionStage<CodingAgentRunResult> stage;
        try {
            stage = command.kind() == RunKind.PROMPT
                    ? core.prompt(command.runId(), command.text())
                    : core.continueRun(command.runId());
        } catch (Throwable failure) {
            finish(entry, null, failure);
            entry.started.completeExceptionally(failure);
            return snapshot(entry);
        }
        synchronized (lock) {
            if (entry.view.status() == RunStatus.ACCEPTED) {
                entry.view = new RunView(sessionId, command.commandId(), command.runId(),
                        RunStatus.RUNNING, false, null, null, false, null);
            }
        }
        stage.whenComplete((result, failure) -> finish(entry, result, failure));
        entry.started.complete(null);
        boolean cancel;
        synchronized (lock) {
            cancel = entry.view.cancelRequested() && !entry.view.status().terminal();
        }
        if (cancel) {
            core.abort(command.runId());
        }
        return snapshot(entry);
    }

    /** Admit an identified input only after the strict core has accepted it. */
    public InputView submit(InputCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        InputEntry entry;
        boolean firstSubmission;
        synchronized (lock) {
            var key = new CommandKey(Operation.INPUT, command.commandId());
            var previous = commands.get(key);
            if (previous != null) {
                entry = (InputEntry) previous;
                if (!entry.command.equals(command)) {
                    throw error(ErrorCode.IDEMPOTENCY_CONFLICT, "commandId belongs to a different input request");
                }
                firstSubmission = false;
            } else {
                requireOpen();
                if (inputs.containsKey(command.inputId())) {
                    throw error(ErrorCode.IDEMPOTENCY_CONFLICT, "inputId belongs to another command");
                }
                if (activeRun == null || !activeRun.command.runId().equals(command.targetRunId())
                        || activeRun.view.cancelRequested() || activeRun.view.status().terminal()) {
                    throw error(ErrorCode.RUN_INPUT_CLOSED, "target run is not accepting input");
                }
                makeRoom();
                entry = new InputEntry(command, activeRun);
                commands.put(key, entry);
                inputs.put(command.inputId(), entry);
                pendingInputAdmissions++;
                firstSubmission = true;
            }
        }
        if (!firstSubmission) {
            try {
                entry.admitted.join();
            } catch (CompletionException failure) {
                if (failure.getCause() instanceof ApiException rejection) {
                    throw rejection;
                }
                throw failure;
            }
            return inputView(entry);
        }
        try {
            entry.targetRun.started.join();
            var record = core.submitInput(new InputRequest(
                    command.inputId(), command.targetRunId(),
                    site.pplee.jcode.codingagent.InputMode.valueOf(command.mode().name()),
                    command.text()));
            entry.admitted.complete(null);
            return toInputView(entry.command, record);
        } catch (RuntimeException failure) {
            var rejection = inputRejection(failure);
            synchronized (lock) {
                commands.remove(new CommandKey(Operation.INPUT, command.commandId()), entry);
                inputs.remove(command.inputId(), entry);
            }
            entry.admitted.completeExceptionally(rejection);
            throw rejection;
        } finally {
            synchronized (lock) {
                pendingInputAdmissions--;
            }
        }
    }

    /** Read the latest retained result for one run. */
    public Optional<RunView> view(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        synchronized (lock) {
            var entry = runs.get(runId);
            return entry == null ? Optional.empty() : Optional.of(entry.view);
        }
    }

    /** Observe terminal settlement without giving the caller cancellation ownership. */
    public CompletionStage<RunView> settled(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        synchronized (lock) {
            var entry = runs.get(runId);
            if (entry == null) {
                throw error(ErrorCode.NOT_FOUND, "run is not retained");
            }
            return entry.finished.copy();
        }
    }

    /** Read an input from the core authority, including its latest entry association. */
    public Optional<InputView> input(String inputId) {
        Objects.requireNonNull(inputId, "inputId must not be null");
        InputCommand command;
        synchronized (lock) {
            var entry = inputs.get(inputId);
            command = entry == null ? null : entry.command;
        }
        return core.input(inputId).map(record -> toInputView(command, record));
    }

    /** Record cancellation without allowing it to overwrite the actual final result. */
    public RunView cancel(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        RunEntry entry;
        boolean signalCore;
        synchronized (lock) {
            entry = runs.get(runId);
            if (entry == null) {
                throw error(ErrorCode.NOT_FOUND, "run is not retained");
            }
            if (entry.view.status().terminal()) {
                return entry.view;
            }
            entry.view = new RunView(sessionId, entry.command.commandId(), runId,
                    RunStatus.CANCELLING, true, null, null, false, null);
            signalCore = entry.started.isDone() && !entry.started.isCompletedExceptionally();
        }
        if (signalCore) {
            core.abort(runId);
        }
        return snapshot(entry);
    }

    /** Close an idle managed session without deleting its history. */
    public void closeIdle() {
        CompletableFuture<Void> completion;
        boolean owner;
        synchronized (lock) {
            if (closed) {
                completion = closing;
                owner = false;
            } else {
                if (activeRun != null || pendingInputAdmissions != 0) {
                    throw error(ErrorCode.SESSION_BUSY, "wait for the active run before closing");
                }
                closed = true;
                closing = new CompletableFuture<>();
                completion = closing;
                owner = true;
            }
        }
        if (!owner) {
            completion.join();
            return;
        }
        try {
            core.close();
            completion.complete(null);
        } catch (RuntimeException | Error failure) {
            completion.completeExceptionally(failure);
            throw failure;
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private InputView inputView(InputEntry entry) {
        var record = core.input(entry.command.inputId()).orElseThrow();
        return toInputView(entry.command, record);
    }

    private RunView snapshot(RunEntry entry) {
        synchronized (lock) {
            return entry.view;
        }
    }

    private InputView toInputView(InputCommand command, InputRecord record) {
        return new InputView(sessionId, command == null ? null : command.commandId(),
                record.inputId(), record.targetRunId(),
                site.pplee.jcode.protocol.InputMode.valueOf(record.request().mode().name()),
                site.pplee.jcode.protocol.InputStatus.valueOf(record.status().name()),
                record.entryId(), record.terminalReason());
    }

    private void finish(RunEntry entry, CodingAgentRunResult result, Throwable failure) {
        var settledInputs = core.inputs(entry.command.runId());
        var actual = unwrap(failure);
        RunStatus status;
        String stopReason = null;
        String text = null;
        boolean textTruncated = false;
        String errorMessage = null;
        if (actual != null) {
            status = actual instanceof CancellationException ? RunStatus.CANCELLED : RunStatus.FAILED;
            errorMessage = status == RunStatus.CANCELLED ? "run cancelled" : "run execution failed";
        } else {
            var assistant = Objects.requireNonNull(result, "run result must not be null").finalMessage();
            stopReason = assistant.stopReason().name();
            var summary = textSummary(assistant.content());
            text = summary.text();
            textTruncated = summary.truncated();
            errorMessage = assistant.errorMessage();
            status = switch (assistant.stopReason()) {
                case ERROR -> RunStatus.FAILED;
                case ABORTED -> RunStatus.CANCELLED;
                default -> RunStatus.COMPLETED;
            };
        }
        RunView finalView;
        synchronized (lock) {
            if (entry.view.status().terminal()) {
                return;
            }
            entry.view = new RunView(sessionId, entry.command.commandId(), entry.command.runId(),
                    status, entry.view.cancelRequested(), stopReason, text,
                    textTruncated, errorMessage);
            for (var input : settledInputs) {
                var related = inputs.get(input.inputId());
                if (related != null) {
                    related.terminal = input.status() != site.pplee.jcode.codingagent.InputStatus.PENDING;
                }
            }
            if (activeRun == entry) {
                activeRun = null;
            }
            finalView = entry.view;
        }
        entry.finished.complete(finalView);
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException completion && completion.getCause() != null) {
            failure = completion.getCause();
        }
        return failure;
    }

    private static TextSummary textSummary(List<Content> content) {
        var text = new StringBuilder();
        boolean truncated = false;
        for (var part : content) {
            if (!(part instanceof Content.Text segment)) {
                continue;
            }
            int remaining = MAX_RESULT_TEXT_CHARS - text.length();
            if (segment.text().length() > remaining) {
                int end = remaining;
                if (end > 0 && end < segment.text().length()
                        && Character.isHighSurrogate(segment.text().charAt(end - 1))
                        && Character.isLowSurrogate(segment.text().charAt(end))) {
                    end--;
                }
                text.append(segment.text(), 0, end);
                truncated = true;
                break;
            }
            text.append(segment.text());
        }
        return new TextSummary(text.toString(), truncated);
    }

    private static ApiException inputRejection(RuntimeException failure) {
        Throwable actual = unwrap(failure);
        if (actual instanceof ApiException api) {
            return api;
        }
        if (actual instanceof IllegalStateException) {
            return error(ErrorCode.RUN_INPUT_CLOSED, "target run is not accepting input");
        }
        if (actual instanceof IllegalArgumentException) {
            return error(ErrorCode.IDEMPOTENCY_CONFLICT, "input identity conflicts with an existing request");
        }
        return new ApiException(ErrorCode.INTERNAL_ERROR, "input admission failed", actual);
    }

    private void makeRoom() {
        if (commands.size() < MAX_RETAINED_COMMANDS) {
            return;
        }
        Iterator<Map.Entry<CommandKey, CommandEntry>> iterator = commands.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getValue().terminal()) {
                continue;
            }
            iterator.remove();
            switch (entry.getValue()) {
                case RunEntry run -> runs.remove(run.command.runId(), run);
                case InputEntry input -> inputs.remove(input.command.inputId(), input);
            }
            return;
        }
        throw error(ErrorCode.CAPACITY_EXCEEDED, "active command retention limit reached");
    }

    private void requireOpen() {
        if (closed) {
            throw error(ErrorCode.SESSION_CLOSED, "session is closed");
        }
    }

    private static ApiException error(ErrorCode code, String message) {
        return new ApiException(code, message);
    }

    private enum Operation { RUN, INPUT }

    private record CommandKey(Operation operation, String commandId) { }

    private record TextSummary(String text, boolean truncated) { }

    private sealed interface CommandEntry permits RunEntry, InputEntry {
        boolean terminal();
    }

    private static final class RunEntry implements CommandEntry {
        private final RunCommand command;
        private final CompletableFuture<Void> started = new CompletableFuture<>();
        private final CompletableFuture<RunView> finished = new CompletableFuture<>();
        private RunView view;

        private RunEntry(RunCommand command, RunView view) {
            this.command = command;
            this.view = view;
        }

        @Override
        public boolean terminal() {
            return view.status().terminal();
        }
    }

    private static final class InputEntry implements CommandEntry {
        private final InputCommand command;
        private final RunEntry targetRun;
        private final CompletableFuture<Void> admitted = new CompletableFuture<>();
        private boolean terminal;

        private InputEntry(InputCommand command, RunEntry targetRun) {
            this.command = command;
            this.targetRun = targetRun;
        }

        @Override
        public boolean terminal() {
            return admitted.isDone() && terminal;
        }
    }
}
