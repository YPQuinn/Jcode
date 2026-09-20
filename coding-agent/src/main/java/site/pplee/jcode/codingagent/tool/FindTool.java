package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/** Ignore-aware, bounded file discovery backed by an explicit ripgrep executable. */
public final class FindTool implements AgentTool<FindToolArguments>, AutoCloseable {
    public static final int MAX_FILES = 2_000;

    private static final Set<String> ARGUMENT_FIELDS = Set.of("pattern", "path", "limit");
    private static final JsonNode SCHEMA = createSchema();

    private final Path workingDirectory;
    private final SearchBackend backend;
    private final boolean ownsBackend;

    public FindTool(Path workingDirectory, SearchConfig config) {
        this.workingDirectory = validateWorkingDirectory(workingDirectory);
        this.backend = RipgrepBackend.open(this.workingDirectory, config);
        this.ownsBackend = true;
    }

    FindTool(Path workingDirectory, SearchBackend backend) {
        this.workingDirectory = validateWorkingDirectory(workingDirectory);
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
        this.ownsBackend = false;
    }

    @Override
    public String name() {
        return "find";
    }

    @Override
    public Class<FindToolArguments> argumentType() {
        return FindToolArguments.class;
    }

    @Override
    public String description() {
        return "Discover ignore-aware regular file paths using a bounded glob post-filter. "
                + "Supports *, ?, and whole-segment **, returns at most 2000 complete JSON-quoted "
                + "relative paths, and excludes hidden, ignored, and larger-than-8-MiB files.";
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
        JsonNode pattern = prepared.get("pattern");
        if (pattern == null || !pattern.isTextual()) {
            throw new IllegalArgumentException("pattern must be a string");
        }
        SearchToolSupport.validateRequiredPattern(pattern.textValue());
        SearchGlob.compile(pattern.textValue());
        JsonNode path = prepared.get("path");
        if (path == null) {
            prepared.put("path", FindToolArguments.DEFAULT_PATH);
        } else if (!path.isTextual()) {
            throw new IllegalArgumentException("path must be a string");
        } else {
            FileToolSupport.validatePath(path.textValue());
        }
        JsonNode limit = prepared.get("limit");
        if (limit == null) {
            prepared.put("limit", FindToolArguments.DEFAULT_LIMIT);
        } else if (!limit.isIntegralNumber() || !limit.canConvertToInt()) {
            throw new IllegalArgumentException("limit must be a 32-bit integer");
        } else if (limit.intValue() < 1 || limit.intValue() > MAX_FILES) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_FILES);
        }
        return prepared;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(
            String toolCallId,
            FindToolArguments arguments,
            ToolUpdateSink updates,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        try {
            cancellation.throwIfCancelled();
            var target = SearchToolSupport.resolveTarget(
                    workingDirectory, arguments.path(), false);
            var collector = new FindCollector(
                    arguments.limit(), SearchGlob.compile(arguments.pattern()));
            var execution = backend.execute(
                    target.workingDirectory(), findCommand(target.backendPath()),
                    collector, cancellation);
            String body = collector.body();
            var notices = collector.notices();
            Optional<String> failure = SearchToolSupport.backendFailure(execution);
            if (failure.isPresent()) {
                String output = SearchToolSupport.failureOutput(body, notices, failure.orElseThrow());
                return completed(new ToolExecutionResult(
                        List.of(new Content.Text(output)), true, false));
            }
            return completed(ToolExecutionResult.success(List.of(
                    new Content.Text(SearchToolSupport.formatOutput(body, notices)))));
        } catch (java.util.concurrent.CancellationException e) {
            return completed(ToolExecutionResult.failure("search cancelled"));
        } catch (IllegalArgumentException e) {
            return completed(ToolExecutionResult.failure("find failed: " + safeArgumentMessage(e)));
        } catch (RuntimeException e) {
            return completed(ToolExecutionResult.failure("find failed: search execution failed"));
        }
    }

    @Override
    public void close() {
        if (ownsBackend) {
            backend.close();
        }
    }

    private static List<String> findCommand(String backendPath) {
        return List.of(
                "--files",
                "--null",
                "--no-config",
                "--no-ignore-global",
                "--threads", SearchToolSupport.SEARCH_THREADS_ARGUMENT,
                "--max-filesize", SearchToolSupport.MAX_FILE_SIZE_ARGUMENT,
                "--",
                backendPath);
    }

    private static Path validateWorkingDirectory(Path workingDirectory) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Path normalized = workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("workingDirectory must be an existing directory");
        }
        return normalized;
    }

    private static String safeArgumentMessage(IllegalArgumentException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "invalid arguments" : message;
    }

    private static JsonNode createSchema() {
        var factory = JsonNodeFactory.instance;
        var properties = factory.objectNode();
        properties.set("pattern", factory.objectNode()
                .put("type", "string")
                .put("description", "Glob using *, ?, and whole-segment **")
                .put("minLength", 1)
                .put("maxLength", SearchGlob.MAX_PATTERN_CHARACTERS));
        properties.set("path", factory.objectNode()
                .put("type", "string")
                .put("description", "Directory to search; defaults to the working directory")
                .put("maxLength", FileToolSupport.MAX_PATH_CHARACTERS));
        properties.set("limit", factory.objectNode()
                .put("type", "integer")
                .put("description", "Maximum matching files; defaults to 1000")
                .put("minimum", 1)
                .put("maximum", MAX_FILES));
        var schema = factory.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("pattern");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static CompletionStage<ToolExecutionResult> completed(ToolExecutionResult result) {
        return CompletableFuture.completedFuture(result);
    }

    private static final class FindCollector implements SearchBackend.OutputCollector {
        private final BoundedRecordFormatter output = new BoundedRecordFormatter(
                MAX_FILES, SearchToolSupport.MAX_OUTPUT_BYTES);
        private final BoundedByteRecordReader records;
        private final int fileLimit;
        private final SearchGlob pattern;
        private final AtomicReference<SearchBackend.StopReason> stopReason = new AtomicReference<>();

        private int files;
        private int unsafeOrOversizedRecords;
        private boolean outputByteLimitReached;

        private FindCollector(int fileLimit, SearchGlob pattern) {
            this.fileLimit = fileLimit;
            this.pattern = pattern;
            this.records = new BoundedByteRecordReader(
                    (byte) 0,
                    SearchToolSupport.MAX_STRUCTURED_RECORD_BYTES,
                    SearchToolSupport.MAX_SCANNED_RECORDS,
                    this::acceptRecord);
        }

        @Override
        public void append(byte[] bytes, int offset, int length) {
            if (stopReason.get() != null) {
                return;
            }
            records.append(bytes, offset, length);
            if (records.scanLimitReached()) {
                stopReason.compareAndSet(null, SearchBackend.StopReason.SCAN_LIMIT);
            }
        }

        @Override
        public void finish() {
            if (stopReason.get() == SearchBackend.StopReason.RESULT_LIMIT) {
                return;
            }
            try {
                records.finish(false);
                if (records.scanLimitReached()) {
                    stopReason.compareAndSet(null, SearchBackend.StopReason.SCAN_LIMIT);
                }
            } catch (RuntimeException e) {
                stopReason.set(SearchBackend.StopReason.PARSE_FAILURE);
            }
        }

        @Override
        public Optional<SearchBackend.StopReason> stopReason() {
            return Optional.ofNullable(stopReason.get());
        }

        String body() {
            return output.recordCount() == 0 ? "(no matching files)" : output.content();
        }

        List<String> notices() {
            var notices = new ArrayList<String>();
            if (outputByteLimitReached) {
                notices.add("Output byte limit reached; additional files were omitted");
            } else if (stopReason.get() == SearchBackend.StopReason.RESULT_LIMIT) {
                notices.add("File limit reached at " + fileLimit + "; narrow the pattern or path");
            }
            if (stopReason.get() == SearchBackend.StopReason.SCAN_LIMIT) {
                notices.add("Structured record scan limit reached after "
                        + SearchToolSupport.MAX_SCANNED_RECORDS + " records");
            }
            int omitted = unsafeOrOversizedRecords + records.oversizedRecords();
            if (omitted > 0) {
                notices.add(omitted
                        + " files omitted because a complete safe path could not be represented");
            }
            notices.add(SearchToolSupport.FILE_SIZE_NOTICE);
            return List.copyOf(notices);
        }

        private void acceptRecord(byte[] record) {
            if (stopReason.get() != null) {
                return;
            }
            final String path;
            try {
                path = SearchToolSupport.normalizeRelativePath(
                        SearchToolSupport.decodeUtf8(record, "search result path"));
                if (!pattern.matches(path)) {
                    return;
                }
            } catch (IllegalArgumentException e) {
                unsafeOrOversizedRecords++;
                return;
            }
            var appendResult = output.append(SearchToolSupport.quote(path));
            if (appendResult == BoundedRecordFormatter.AppendResult.ADDED) {
                files++;
                if (files >= fileLimit) {
                    stopReason.compareAndSet(null, SearchBackend.StopReason.RESULT_LIMIT);
                }
            } else if (appendResult == BoundedRecordFormatter.AppendResult.OUTPUT_FULL) {
                outputByteLimitReached = true;
                stopReason.compareAndSet(null, SearchBackend.StopReason.RESULT_LIMIT);
            } else {
                unsafeOrOversizedRecords++;
            }
        }
    }
}
