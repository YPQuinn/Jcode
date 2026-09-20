package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded POSIX Bash command tool with explicit process and environment ownership. */
public final class BashTool implements AgentTool<BashToolArguments>, AutoCloseable {
    public static final int MAX_COMMAND_BYTES = 64 * 1024;
    public static final int MAX_OUTPUT_LINES = 2_000;
    public static final int MAX_OUTPUT_BYTES = 50 * 1024;
    public static final int MAX_STATUS_BYTES = 1_024;

    private static final Set<String> ARGUMENT_FIELDS = Set.of("command", "timeout");
    private static final JsonNode SCHEMA = createSchema();

    private final Path workingDirectory;
    private final BashConfig config;
    private final ProcessExecutor processExecutor;
    private final TaskScheduler updateScheduler;
    private final ExecutorService updateExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public BashTool(Path workingDirectory, BashConfig config) {
        this(requireWorkingDirectory(workingDirectory),
                Objects.requireNonNull(config, "config must not be null"),
                new ProcessRunner());
    }

    BashTool(Path workingDirectory, BashConfig config, ProcessExecutor processExecutor) {
        this.workingDirectory = requireWorkingDirectory(workingDirectory);
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.processExecutor = Objects.requireNonNull(
                processExecutor, "processExecutor must not be null");
        this.updateScheduler = new ScheduledExecutorTaskScheduler();
        this.updateExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public Class<BashToolArguments> argumentType() {
        return BashToolArguments.class;
    }

    @Override
    public String description() {
        return "Execute one command with an explicitly configured POSIX Bash in the working directory. "
                + "The shell is started with --noprofile --norc, stderr is merged into stdout, "
                + "and only a bounded output tail is returned. Timeout is in whole seconds.";
    }

    @Override
    public JsonNode parametersSchema() {
        return SCHEMA.deepCopy();
    }

    @Override
    public JsonNode prepareArguments(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("arguments must be an object");
        }
        var prepared = ((ObjectNode) arguments).deepCopy();
        prepared.fieldNames().forEachRemaining(field -> {
            if (!ARGUMENT_FIELDS.contains(field)) {
                throw new IllegalArgumentException("unknown argument field: " + field);
            }
        });
        JsonNode command = prepared.get("command");
        if (command == null || !command.isTextual()) {
            throw new IllegalArgumentException("command must be a string");
        }
        if (command.textValue().isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        FileToolSupport.utf8Length(command.textValue(), MAX_COMMAND_BYTES, "command");

        JsonNode timeout = prepared.get("timeout");
        long timeoutSeconds;
        if (timeout == null) {
            timeoutSeconds = config.defaultTimeout().toSeconds();
            prepared.put("timeout", timeoutSeconds);
        } else {
            if (!timeout.isIntegralNumber() || !timeout.canConvertToLong()) {
                throw new IllegalArgumentException("timeout must be an integer");
            }
            timeoutSeconds = timeout.longValue();
        }
        validateTimeout(timeoutSeconds);
        return prepared;
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(
            String toolCallId,
            BashToolArguments arguments,
            ToolUpdateSink updates,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(updates, "updates must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        if (closed.get()) {
            return completed(ToolExecutionResult.failure("bash failed: tool is closed"));
        }

        final long timeoutSeconds;
        try {
            validateCommand(arguments.command());
            timeoutSeconds = arguments.timeout() == null
                    ? config.defaultTimeout().toSeconds()
                    : arguments.timeout();
            validateTimeout(timeoutSeconds);
        } catch (IllegalArgumentException e) {
            return completed(ToolExecutionResult.failure("bash failed: " + e.getMessage()));
        }

        var output = new ProcessOutputBuffer(MAX_OUTPUT_LINES, MAX_OUTPUT_BYTES);
        var processCancellation = new CancellationSource();
        try (var registration = cancellation.onCancellation(processCancellation::cancel);
             var publisher = new LatestToolUpdatePublisher(
                     updates,
                     () -> new Content.Text(formatLiveOutput(output.snapshot())),
                     updateScheduler,
                     updateExecutor,
                     processCancellation::cancel)) {
            ProcessRunResult processResult;
            try {
                var request = new ProcessRequest(
                        List.of(config.executable().toString(),
                                "--noprofile", "--norc", "-c", arguments.command()),
                        workingDirectory,
                        config.environment(),
                        Duration.ofSeconds(timeoutSeconds),
                        ProcessRequest.OutputMode.MERGED);
                processResult = processExecutor.run(request,
                        (channel, bytes, offset, length) -> {
                            output.append(bytes, offset, length);
                            publisher.markDirty();
                        }, processCancellation.signal());
            } catch (RuntimeException e) {
                processResult = ProcessRunResult.failed("process execution failed");
            }
            output.finish();
            if (output.snapshot().totalUtf8Bytes() > 0 || output.snapshot().invalidUtf8()) {
                publisher.markDirty();
            }
            publisher.finish();
            return completed(toToolResult(processResult, output.snapshot(), timeoutSeconds));
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            processExecutor.close();
            updateScheduler.close();
            updateExecutor.shutdownNow();
        }
    }

    private ToolExecutionResult toToolResult(
            ProcessRunResult result,
            ProcessOutputSnapshot output,
            long timeoutSeconds
    ) {
        String status = switch (result.termination()) {
            case EXITED -> "Process exited with code " + result.exitCode();
            case TIMED_OUT -> "Process timed out after " + timeoutSeconds + " seconds";
            case CANCELLED -> "Process cancelled";
            case START_FAILED -> "Process could not be started";
            case RESOURCE_BUSY -> "Process capacity is exhausted";
            case FAILED -> result.diagnostic().orElse("Process execution failed");
            case TERMINATION_FAILED -> "Process could not be terminated cleanly";
        };
        String text = formatFinalOutput(output, status);
        return result.termination() == ProcessRunResult.Termination.EXITED
                && result.exitCode() == 0
                ? ToolExecutionResult.success(List.of(new Content.Text(text)))
                : ToolExecutionResult.failure(text);
    }

    private static String formatLiveOutput(ProcessOutputSnapshot snapshot) {
        return formatOutput(snapshot, null, false);
    }

    private static String formatFinalOutput(ProcessOutputSnapshot snapshot, String status) {
        return formatOutput(snapshot, status, true);
    }

    private static String formatOutput(
            ProcessOutputSnapshot snapshot,
            String processStatus,
            boolean useEmptyPlaceholder
    ) {
        String body = snapshot.content();
        if (body.isEmpty() && useEmptyPlaceholder) {
            body = "(no output)";
        }
        var diagnostics = new StringBuilder();
        if (snapshot.truncated()) {
            if (snapshot.firstLinePartial()) {
                appendDiagnostic(diagnostics,
                        "Output truncated: showing the last " + snapshot.outputUtf8Bytes()
                                + " bytes of line " + snapshot.totalLines());
            } else {
                long firstLine = Math.max(1,
                        snapshot.totalLines() - snapshot.outputLines() + 1);
                if (snapshot.truncatedBy() == ProcessOutputSnapshot.TruncatedBy.LINES) {
                    appendDiagnostic(diagnostics,
                            "Output truncated: showing lines " + firstLine + '-'
                                    + snapshot.totalLines() + " of " + snapshot.totalLines());
                } else {
                    appendDiagnostic(diagnostics,
                            "Output truncated: showing the last " + snapshot.outputUtf8Bytes()
                                    + " bytes across lines " + firstLine + '-'
                                    + snapshot.totalLines());
                }
            }
        }
        if (snapshot.invalidUtf8()) {
            appendDiagnostic(diagnostics, "Invalid UTF-8 bytes were replaced");
        }
        if (processStatus != null) {
            appendDiagnostic(diagnostics, processStatus);
        }
        String boundedDiagnostics = utf8Prefix(diagnostics.toString(), MAX_STATUS_BYTES);
        if (boundedDiagnostics.isEmpty()) {
            return body;
        }
        if (body.isEmpty()) {
            return '[' + boundedDiagnostics + ']';
        }
        return body + (body.endsWith("\n") ? "\n" : "\n\n")
                + '[' + boundedDiagnostics.replace("\n", "]\n[") + ']';
    }

    private static void appendDiagnostic(StringBuilder diagnostics, String diagnostic) {
        if (!diagnostics.isEmpty()) {
            diagnostics.append('\n');
        }
        diagnostics.append(diagnostic);
    }

    private static String utf8Prefix(String value, int maximumBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maximumBytes) {
            return value;
        }
        int bytes = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int width = new String(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maximumBytes - width) {
                break;
            }
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    private static Path requireWorkingDirectory(Path workingDirectory) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Path absolute = workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("workingDirectory must be an existing directory");
        }
        return absolute;
    }

    private void validateTimeout(long timeoutSeconds) {
        long maximum = config.maximumTimeout().toSeconds();
        if (timeoutSeconds < 1 || timeoutSeconds > maximum) {
            throw new IllegalArgumentException(
                    "timeout must be between 1 and " + maximum + " seconds");
        }
    }

    private static void validateCommand(String command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        FileToolSupport.utf8Length(command, MAX_COMMAND_BYTES, "command");
    }

    private static CompletionStage<ToolExecutionResult> completed(ToolExecutionResult result) {
        return CompletableFuture.completedStage(result);
    }

    private static JsonNode createSchema() {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        var properties = root.putObject("properties");
        properties.putObject("command")
                .put("type", "string")
                .put("minLength", 1)
                .put("maxLength", MAX_COMMAND_BYTES)
                .put("description", "POSIX Bash source to execute in the working directory");
        properties.putObject("timeout")
                .put("type", "integer")
                .put("minimum", 1)
                .put("description", "Timeout in whole seconds; defaults to the configured value");
        root.putArray("required").add("command");
        return root;
    }
}
