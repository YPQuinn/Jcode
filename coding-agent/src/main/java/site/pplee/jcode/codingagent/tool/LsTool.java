package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/** Bounded, non-recursive local directory listing tool. */
public final class LsTool implements AgentTool<LsToolArguments> {
    public static final int MAX_ENTRIES = 2_000;
    public static final int MAX_OUTPUT_BYTES = 50 * 1_024;
    public static final int MAX_SCANNED_ENTRIES = 100_000;

    private static final int MAX_STATUS_LINES = 4;
    private static final int MAX_STATUS_BYTES = 1_024;
    private static final int MAX_ENTRY_NAME_BYTES = FileToolSupport.MAX_PATH_CHARACTERS * 4;
    private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(30);
    private static final Set<String> ARGUMENT_FIELDS = Set.of("path", "limit");
    private static final JsonNode SCHEMA = createSchema();
    private static final Comparator<ListedEntry> ENTRY_ORDER = (left, right) -> {
        int groupOrder = Integer.compare(left.type().sortGroup(), right.type().sortGroup());
        return groupOrder != 0 ? groupOrder : compareCodePoints(left.name(), right.name());
    };

    private final Path workingDirectory;
    private final int maximumScannedEntries;
    private final long scanTimeoutNanos;
    private final LongSupplier nanoTime;

    public LsTool(Path workingDirectory) {
        this(workingDirectory, MAX_SCANNED_ENTRIES, SCAN_TIMEOUT, System::nanoTime);
    }

    LsTool(
            Path workingDirectory,
            int maximumScannedEntries,
            Duration scanTimeout,
            LongSupplier nanoTime
    ) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.workingDirectory)) {
            throw new IllegalArgumentException("workingDirectory must be an existing directory");
        }
        if (maximumScannedEntries < 1) {
            throw new IllegalArgumentException("maximumScannedEntries must be positive");
        }
        Objects.requireNonNull(scanTimeout, "scanTimeout must not be null");
        if (scanTimeout.isZero() || scanTimeout.isNegative()) {
            throw new IllegalArgumentException("scanTimeout must be positive");
        }
        this.maximumScannedEntries = maximumScannedEntries;
        this.scanTimeoutNanos = scanTimeout.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
    }

    @Override
    public String name() {
        return "ls";
    }

    @Override
    public Class<LsToolArguments> argumentType() {
        return LsToolArguments.class;
    }

    @Override
    public String description() {
        return "List direct children of a directory, including hidden entries, without recursion. "
                + "Results distinguish directories, files, symbolic links, and other entries; "
                + "directories sort first and names use Unicode code point order. "
                + "The default limit is 1000 entries, the maximum is 2000, and output is limited to 50 KiB.";
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
        JsonNode path = prepared.get("path");
        if (path == null) {
            prepared.put("path", LsToolArguments.DEFAULT_PATH);
        } else if (!path.isTextual()) {
            throw new IllegalArgumentException("path must be a string");
        } else {
            FileToolSupport.validatePath(path.textValue());
        }
        JsonNode limit = prepared.get("limit");
        if (limit == null) {
            prepared.put("limit", LsToolArguments.DEFAULT_LIMIT);
        } else if (!limit.isIntegralNumber() || !limit.canConvertToInt()) {
            throw new IllegalArgumentException("limit must be a 32-bit integer");
        } else {
            validateLimit(limit.intValue());
        }
        return prepared;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(
            String toolCallId,
            LsToolArguments arguments,
            ToolUpdateSink updates,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        try {
            cancellation.throwIfCancelled();
            FileToolSupport.validatePath(arguments.path());
            validateLimit(arguments.limit());
            Path directory = resolve(arguments.path());
            if (!Files.exists(directory)) {
                return completedFailure("ls failed: path does not exist");
            }
            if (!Files.isDirectory(directory)) {
                return completedFailure("ls failed: path is not a directory");
            }
            ScanResult scan = scan(directory, arguments.limit(), cancellation);
            cancellation.throwIfCancelled();
            return completed(format(scan, arguments.limit()));
        } catch (CancellationException e) {
            return completedFailure("ls cancelled");
        } catch (DirectoryIteratorException e) {
            return completedFailure("ls failed: " + FileToolSupport.safeMessage(e.getCause()));
        } catch (IOException | RuntimeException e) {
            return completedFailure("ls failed: " + FileToolSupport.safeMessage(e));
        }
    }

    private ScanResult scan(
            Path directory,
            int requestedLimit,
            CancellationSignal cancellation
    ) throws IOException {
        var selected = new PriorityQueue<ListedEntry>(requestedLimit, ENTRY_ORDER.reversed());
        long start = nanoTime.getAsLong();
        int scanned = 0;
        int eligible = 0;
        int unreadable = 0;
        int invalidName = 0;
        boolean scanLimitReached = false;
        boolean scanTimeoutReached = false;
        try (var stream = Files.newDirectoryStream(directory)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                cancellation.throwIfCancelled();
                if (scanned >= maximumScannedEntries) {
                    scanLimitReached = true;
                    break;
                }
                if (elapsedNanos(start, nanoTime.getAsLong()) >= scanTimeoutNanos) {
                    scanTimeoutReached = true;
                    break;
                }
                Path child = iterator.next();
                scanned++;
                final ListedEntry entry;
                try {
                    String name = child.getFileName().toString();
                    FileToolSupport.utf8Length(name, MAX_ENTRY_NAME_BYTES, "directory entry name");
                    entry = new ListedEntry(name, entryType(child));
                } catch (IOException | SecurityException | IllegalArgumentException e) {
                    if (e instanceof IllegalArgumentException) {
                        invalidName++;
                    } else {
                        unreadable++;
                    }
                    continue;
                }
                eligible++;
                if (selected.size() < requestedLimit) {
                    selected.add(entry);
                } else if (ENTRY_ORDER.compare(entry, selected.element()) < 0) {
                    selected.remove();
                    selected.add(entry);
                }
            }
        }
        var ordered = new ArrayList<>(selected);
        ordered.sort(ENTRY_ORDER);
        return new ScanResult(
                ordered,
                eligible,
                unreadable,
                invalidName,
                scanLimitReached,
                scanTimeoutReached);
    }

    private ToolExecutionResult format(ScanResult scan, int requestedLimit) {
        var records = new BoundedRecordFormatter(MAX_ENTRIES, MAX_OUTPUT_BYTES);
        int recordsOmitted = 0;
        for (ListedEntry entry : scan.entries()) {
            String quotedName = JsonNodeFactory.instance.textNode(entry.name()).toString();
            var appendResult = records.append(entry.type().label() + ' ' + quotedName);
            if (appendResult != BoundedRecordFormatter.AppendResult.ADDED) {
                recordsOmitted++;
            }
        }

        String body;
        if (records.recordCount() > 0) {
            body = records.content();
        } else if (scan.eligibleEntries() == 0
                && (scan.scanLimitReached() || scan.scanTimeoutReached())) {
            body = "(no entries collected before the directory scan stopped)";
        } else if (scan.eligibleEntries() == 0
                && scan.unreadableEntries() == 0
                && scan.invalidNames() == 0) {
            body = "(empty directory)";
        } else if (scan.eligibleEntries() == 0) {
            body = "(no readable directory entries)";
        } else {
            body = "(no directory entries fit the output limits)";
        }

        var notices = new ArrayList<String>();
        if (scan.scanLimitReached()) {
            notices.add("Directory scan limit reached after " + maximumScannedEntries
                    + " entries; results are an ordered subset of scanned entries");
        } else if (scan.scanTimeoutReached()) {
            notices.add("Directory scan timeout reached; results are an ordered subset of scanned entries");
        }
        if (scan.eligibleEntries() > requestedLimit) {
            notices.add("Result limit reached at " + requestedLimit
                    + " entries; increase limit or narrow the directory");
        }
        int omitted = recordsOmitted + scan.invalidNames();
        if (omitted > 0) {
            notices.add(omitted + " entries omitted because a complete record did not fit output limits");
        }
        if (scan.unreadableEntries() > 0) {
            notices.add(scan.unreadableEntries() + " entries omitted because metadata could not be read");
        }
        String output = BoundedRecordFormatter.withNotices(
                body, notices, MAX_STATUS_LINES, MAX_STATUS_BYTES);
        return ToolExecutionResult.success(List.of(new Content.Text(output)));
    }

    /** Preserve native symlink and parent traversal semantics. */
    private Path resolve(String input) {
        Path requested = Path.of(input);
        return requested.isAbsolute() ? requested : workingDirectory.resolve(requested);
    }

    private static EntryType entryType(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
            return EntryType.SYMLINK;
        }
        if (attributes.isDirectory()) {
            return EntryType.DIRECTORY;
        }
        if (attributes.isRegularFile()) {
            return EntryType.FILE;
        }
        return EntryType.OTHER;
    }

    private static long elapsedNanos(long start, long current) {
        return current < start ? 0 : current - start;
    }

    private static void validateLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (limit > MAX_ENTRIES) {
            throw new IllegalArgumentException("limit must not exceed " + MAX_ENTRIES);
        }
    }

    private static int compareCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftCodePoint = left.codePointAt(leftIndex);
            int rightCodePoint = right.codePointAt(rightIndex);
            int comparison = Integer.compare(leftCodePoint, rightCodePoint);
            if (comparison != 0) {
                return comparison;
            }
            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }

    private static JsonNode createSchema() {
        var factory = JsonNodeFactory.instance;
        var properties = factory.objectNode();
        properties.set("path", factory.objectNode()
                .put("type", "string")
                .put("description", "Directory to list; defaults to the session working directory")
                .put("maxLength", FileToolSupport.MAX_PATH_CHARACTERS));
        properties.set("limit", factory.objectNode()
                .put("type", "integer")
                .put("description", "Maximum entries to return; defaults to 1000")
                .put("minimum", 1)
                .put("maximum", MAX_ENTRIES));
        var schema = factory.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static CompletionStage<ToolExecutionResult> completed(ToolExecutionResult result) {
        return CompletableFuture.completedFuture(result);
    }

    private static CompletionStage<ToolExecutionResult> completedFailure(String message) {
        return completed(ToolExecutionResult.failure(message));
    }

    private enum EntryType {
        DIRECTORY("directory", 0),
        FILE("file", 1),
        SYMLINK("symlink", 1),
        OTHER("other", 1);

        private final String label;
        private final int sortGroup;

        EntryType(String label, int sortGroup) {
            this.label = label;
            this.sortGroup = sortGroup;
        }

        String label() {
            return label;
        }

        int sortGroup() {
            return sortGroup;
        }
    }

    private record ListedEntry(String name, EntryType type) {
    }

    private record ScanResult(
            List<ListedEntry> entries,
            int eligibleEntries,
            int unreadableEntries,
            int invalidNames,
            boolean scanLimitReached,
            boolean scanTimeoutReached
    ) {
    }
}
