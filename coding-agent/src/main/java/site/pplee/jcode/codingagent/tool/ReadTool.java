package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Local, bounded file reader exposed as the {@code read} agent tool. */
public final class ReadTool implements AgentTool<ReadToolArguments> {
    public static final int MAX_TEXT_LINES = 2000;
    public static final int MAX_TEXT_BYTES = 50 * 1024;
    public static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    private static final Set<String> ARGUMENT_FIELDS = Set.of("path", "offset", "limit");
    private static final JsonNode SCHEMA = createSchema();

    private final Path workingDirectory;

    public ReadTool(Path workingDirectory) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.workingDirectory)) {
            throw new IllegalArgumentException("workingDirectory must be an existing directory");
        }
    }

    @Override
    public String name() {
        return "read";
    }

    @Override
    public Class<ReadToolArguments> argumentType() {
        return ReadToolArguments.class;
    }

    @Override
    public String description() {
        return "Read a UTF-8 text file or return a supported JPEG, PNG, or WEBP image as an attachment. "
                + "Text output is limited to 2000 complete lines or 50 KiB; "
                + "use offset and limit to continue reading large files without losing whole lines. "
                + "A physical line larger than 50 KiB cannot be paged.";
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
        validateOptionalInteger(prepared, "offset", ReadToolArguments.DEFAULT_OFFSET);
        validateOptionalInteger(prepared, "limit", ReadToolArguments.DEFAULT_LIMIT);
        return prepared;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(
            String toolCallId,
            ReadToolArguments arguments,
            ToolUpdateSink updates,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        try {
            cancellation.throwIfCancelled();
            Path path = resolve(arguments.path());
            if (!Files.exists(path)) {
                return completedFailure("file does not exist: " + path);
            }
            if (!Files.isRegularFile(path)) {
                return completedFailure("path is not a regular file: " + path);
            }

            String extension = extension(path);
            return switch (extension) {
                case "jpg", "jpeg" -> completed(readImage(path, "image/jpeg", ImageKind.JPEG, cancellation));
                case "png" -> completed(readImage(path, "image/png", ImageKind.PNG, cancellation));
                case "webp" -> completed(readImage(path, "image/webp", ImageKind.WEBP, cancellation));
                case "gif" -> completedFailure("unsupported image format: GIF");
                case "bmp" -> completedFailure("unsupported image format: BMP");
                default -> completed(readText(path, arguments, cancellation));
            };
        } catch (CancellationException e) {
            return completedFailure("read cancelled");
        } catch (LineTooLongException e) {
            return completedFailure(e.getMessage());
        } catch (IOException | RuntimeException e) {
            return completedFailure("read failed: " + safeMessage(e));
        }
    }

    private ToolExecutionResult readText(
            Path path,
            ReadToolArguments arguments,
            CancellationSignal cancellation
    ) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        TruncatedOutput output;
        try (var input = Files.newInputStream(path);
             var reader = new InputStreamReader(input, decoder)) {
            output = OutputTruncator.truncate(
                    reader,
                    arguments.offset(),
                    Math.min(arguments.limit(), MAX_TEXT_LINES),
                    MAX_TEXT_BYTES,
                    cancellation);
        }
        cancellation.throwIfCancelled();

        if (output.outputLines() == 0) {
            String message = arguments.offset() == 1 && Files.size(path) == 0
                    ? "(empty file)"
                    : "(offset " + arguments.offset() + " is beyond end of file)";
            return textSuccess(message);
        }

        var text = new StringBuilder(output.content());
        if (output.truncated()) {
            int first = arguments.offset();
            int last = Math.addExact(first, output.outputLines() - 1);
            String reason = output.outputLines() >= Math.min(arguments.limit(), MAX_TEXT_LINES)
                    ? "line limit reached"
                    : "byte limit reached";
            if (!text.isEmpty() && text.charAt(text.length() - 1) == '\n') {
                text.append('\n');
            } else {
                text.append("\n\n");
            }
            text.append("[Showing lines ").append(first).append('-').append(last)
                    .append("; ").append(reason).append("; continue with offset=")
                    .append(output.nextOffset().orElseThrow()).append(']');
        }
        return textSuccess(text.toString());
    }

    private ToolExecutionResult readImage(
            Path path,
            String mediaType,
            ImageKind imageKind,
            CancellationSignal cancellation
    ) throws IOException {
        long declaredSize = Files.size(path);
        if (declaredSize > MAX_IMAGE_BYTES) {
            return ToolExecutionResult.failure("image exceeds the 20 MiB limit: " + path);
        }
        cancellation.throwIfCancelled();
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
        }
        cancellation.throwIfCancelled();
        if (bytes.length > MAX_IMAGE_BYTES) {
            return ToolExecutionResult.failure("image exceeds the 20 MiB limit: " + path);
        }
        if (!imageKind.matches(bytes)) {
            return ToolExecutionResult.failure("image extension and file signature do not match: " + path);
        }
        String encoded = Base64.getEncoder().encodeToString(bytes);
        return ToolExecutionResult.success(java.util.List.of(new Content.Image(mediaType, encoded)));
    }

    /** Preserve filesystem parent traversal, including segments following symbolic links. */
    private Path resolve(String input) {
        Path requested = Path.of(input);
        return requested.isAbsolute() ? requested : workingDirectory.resolve(requested);
    }

    private static void validateOptionalInteger(ObjectNode arguments, String field, int defaultValue) {
        JsonNode value = arguments.get(field);
        if (value == null) {
            arguments.put(field, defaultValue);
            return;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be a 32-bit integer");
        }
        if (value.intValue() < 1) {
            throw new IllegalArgumentException(field + " must be at least 1");
        }
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int separator = name.lastIndexOf('.');
        return separator < 0 ? "" : name.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private static ToolExecutionResult textSuccess(String text) {
        return ToolExecutionResult.success(java.util.List.of(new Content.Text(text)));
    }

    private static CompletionStage<ToolExecutionResult> completed(ToolExecutionResult result) {
        return CompletableFuture.completedFuture(result);
    }

    private static CompletionStage<ToolExecutionResult> completedFailure(String message) {
        return completed(ToolExecutionResult.failure(message));
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static JsonNode createSchema() {
        var factory = JsonNodeFactory.instance;
        var root = factory.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        var properties = root.putObject("properties");
        properties.putObject("path")
                .put("type", "string")
                .put("description", "Path to the file, relative to the working directory or absolute");
        properties.putObject("offset")
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", Integer.MAX_VALUE)
                .put("description", "First physical line to return, one-based");
        properties.putObject("limit")
                .put("type", "integer")
                .put("minimum", 1)
                .put("maximum", Integer.MAX_VALUE)
                .put("description", "Maximum number of complete lines to return");
        root.putArray("required").add("path");
        return root;
    }

    private enum ImageKind {
        JPEG {
            @Override
            boolean matches(byte[] bytes) {
                return bytes.length >= 3
                        && (bytes[0] & 0xff) == 0xff
                        && (bytes[1] & 0xff) == 0xd8
                        && (bytes[2] & 0xff) == 0xff;
            }
        },
        PNG {
            private final byte[] signature = new byte[] {
                    (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
            };

            @Override
            boolean matches(byte[] bytes) {
                return bytes.length >= signature.length
                        && ByteBuffer.wrap(bytes, 0, signature.length).equals(ByteBuffer.wrap(signature));
            }
        },
        WEBP {
            @Override
            boolean matches(byte[] bytes) {
                return bytes.length >= 12
                        && ascii(bytes, 0, "RIFF")
                        && ascii(bytes, 8, "WEBP");
            }
        };

        abstract boolean matches(byte[] bytes);

        static boolean ascii(byte[] bytes, int offset, String expected) {
            for (int index = 0; index < expected.length(); index++) {
                if (bytes[offset + index] != (byte) expected.charAt(index)) {
                    return false;
                }
            }
            return true;
        }
    }
}
