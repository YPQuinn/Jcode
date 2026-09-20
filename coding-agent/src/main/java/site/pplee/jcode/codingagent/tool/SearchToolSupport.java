package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Shared limits, decoding, formatting, and terminal mapping for search tools. */
final class SearchToolSupport {
    static final int MAX_PATTERN_CHARACTERS = 4_096;
    static final int MAX_PATTERN_BYTES = 64 * 1_024;
    static final int MAX_STRUCTURED_RECORD_BYTES = 64 * 1_024;
    static final int MAX_SCANNED_RECORDS = 100_000;
    static final int MAX_OUTPUT_RECORDS = 2_000;
    static final int MAX_OUTPUT_BYTES = 50 * 1_024;
    static final int MAX_STATUS_LINES = 4;
    static final int MAX_STATUS_BYTES = 1_024;

    private SearchToolSupport() {
    }

    static String validateRequiredPattern(String pattern) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        if (pattern.isEmpty()) {
            throw new IllegalArgumentException("pattern must not be empty");
        }
        if (pattern.length() > MAX_PATTERN_CHARACTERS) {
            throw new IllegalArgumentException("pattern exceeds the length limit");
        }
        if (pattern.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("pattern must not contain NUL");
        }
        FileToolSupport.utf8Length(pattern, MAX_PATTERN_BYTES, "pattern");
        return pattern;
    }

    static String validateOptionalGlob(String glob) {
        if (glob == null) {
            return null;
        }
        return validateRequiredPattern(glob);
    }

    static SearchTarget resolveTarget(Path workingDirectory, String input, boolean allowFile) {
        String validated = FileToolSupport.validatePath(input);
        final Path requested;
        try {
            requested = Path.of(validated);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("invalid search path", e);
        }
        Path resolved = requested.isAbsolute() ? requested : workingDirectory.resolve(requested);
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("search path does not exist");
        }
        if (Files.isDirectory(resolved)) {
            return new SearchTarget(resolved, ".", true);
        }
        if (allowFile && Files.isRegularFile(resolved)) {
            Path parent = resolved.getParent();
            Path fileName = resolved.getFileName();
            if (parent == null || fileName == null) {
                throw new IllegalArgumentException("search file must have a parent and file name");
            }
            // Even after --, a bare dash selects stdin rather than a file.
            return new SearchTarget(parent, "./" + fileName, false);
        }
        throw new IllegalArgumentException(
                allowFile ? "search path is not a regular file or directory"
                        : "search path is not a directory");
    }

    static Optional<String> backendFailure(SearchBackend.ExecutionResult execution) {
        return backendFailure(execution, true);
    }

    /** Only text search uses exit code one for a successful search with no matches. */
    static Optional<String> backendFailure(SearchBackend.ExecutionResult execution, boolean acceptsNoMatchExit) {
        if (execution.stopOrigin() == SearchBackend.StopOrigin.CALLER_CANCELLATION) {
            return Optional.of("search cancelled");
        }
        Optional<SearchBackend.StopReason> collectorReason = execution.collectorStopReason();
        if (collectorReason.orElse(null) == SearchBackend.StopReason.PARSE_FAILURE) {
            return Optional.of("search backend returned malformed structured output");
        }
        ProcessRunResult result = execution.processResult();
        if (execution.stopOrigin() == SearchBackend.StopOrigin.COLLECTOR
                && collectorReason.isPresent()
                && collectorReason.get() != SearchBackend.StopReason.PARSE_FAILURE
                && (result.termination() == ProcessRunResult.Termination.CANCELLED
                || (result.termination() == ProcessRunResult.Termination.EXITED
                && (result.exitCode() == 0 || acceptsNoMatchExit && result.exitCode() == 1)))) {
            return Optional.empty();
        }
        return switch (result.termination()) {
            case EXITED -> result.exitCode() == 0 || acceptsNoMatchExit && result.exitCode() == 1
                    ? Optional.empty()
                    : Optional.of(classifyBackendError(execution.standardError()));
            case TIMED_OUT -> Optional.of("search timed out after 30 seconds");
            case CANCELLED -> Optional.of("search cancelled");
            case START_FAILED -> Optional.of("search process could not be started");
            case RESOURCE_BUSY -> Optional.of("search process capacity is exhausted");
            case FAILED -> Optional.of("search process failed");
            case TERMINATION_FAILED -> Optional.of("search process could not be terminated");
        };
    }

    static String decodeUtf8(byte[] bytes, String label) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(label + " is not valid UTF-8", e);
        }
    }

    static String decodeTextOrBytes(JsonNode node, String label) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        JsonNode text = node.get("text");
        JsonNode bytes = node.get("bytes");
        if (text != null && text.isTextual() && bytes == null) {
            String value = text.textValue();
            FileToolSupport.utf8Length(value, MAX_STRUCTURED_RECORD_BYTES, label);
            return value;
        }
        if (bytes != null && bytes.isTextual() && text == null) {
            try {
                return decodeUtf8(Base64.getDecoder().decode(bytes.textValue()), label);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(label + " bytes are invalid", e);
            }
        }
        throw new IllegalArgumentException(label + " must contain exactly one text or bytes field");
    }

    static String normalizeRelativePath(String path) {
        Objects.requireNonNull(path, "path must not be null");
        String normalized = File.separatorChar == '\\' ? path.replace('\\', '/') : path;
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isEmpty() || normalized.startsWith("/")
                || normalized.equals("..") || normalized.startsWith("../")) {
            throw new IllegalArgumentException("search backend returned a non-relative path");
        }
        if (normalized.length() > FileToolSupport.MAX_PATH_CHARACTERS) {
            throw new IllegalArgumentException("search result path exceeds the length limit");
        }
        if (normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("search result path contains NUL");
        }
        FileToolSupport.utf8Length(normalized, MAX_STRUCTURED_RECORD_BYTES, "search result path");
        return normalized;
    }

    static String quote(String text) {
        return JsonNodeFactory.instance.textNode(text).toString();
    }

    static String stripLineEnding(String text) {
        if (text.endsWith("\r\n")) {
            return text.substring(0, text.length() - 2);
        }
        if (text.endsWith("\n") || text.endsWith("\r")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    static String truncateCodePoints(String text, int maximumCodePoints) {
        if (text.codePointCount(0, text.length()) <= maximumCodePoints) {
            return text;
        }
        String suffix = "… [truncated]";
        int suffixCodePoints = suffix.codePointCount(0, suffix.length());
        int prefixCodePoints = maximumCodePoints - suffixCodePoints;
        int end = text.offsetByCodePoints(0, prefixCodePoints);
        return text.substring(0, end) + suffix;
    }

    static String formatOutput(String body, List<String> notices) {
        return BoundedRecordFormatter.withNotices(
                body, notices, MAX_STATUS_LINES, MAX_STATUS_BYTES);
    }

    static String failureOutput(String body, List<String> notices, String failure) {
        var combined = new java.util.ArrayList<>(notices);
        combined.add(0, "Search failed: " + failure);
        return formatOutput(body, combined);
    }

    private static String classifyBackendError(String standardError) {
        String diagnostic = standardError.strip().replace("\r", "").replace("\n", " | ");
        return diagnostic.isEmpty() ? "search backend reported an error"
                : truncateCodePoints(diagnostic, MAX_STATUS_BYTES);
    }

    record SearchTarget(Path workingDirectory, String backendPath, boolean directory) {
        SearchTarget {
            workingDirectory = Objects.requireNonNull(
                    workingDirectory, "workingDirectory must not be null");
            backendPath = Objects.requireNonNull(backendPath, "backendPath must not be null");
        }
    }
}
