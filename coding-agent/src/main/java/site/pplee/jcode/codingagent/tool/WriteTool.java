package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Native whole-file writer exposed as the {@code write} agent tool. */
public final class WriteTool implements AgentTool<WriteToolArguments> {
    private static final Set<String> ARGUMENT_FIELDS = Set.of("path", "content");
    private static final JsonNode SCHEMA = createSchema();

    private final LocalFileAccess writer;

    public WriteTool(Path workingDirectory) {
        Path validatedDirectory = validateWorkingDirectory(workingDirectory);
        this.writer = new LocalFileAccess(validatedDirectory);
    }

    @Override
    public String name() {
        return "write";
    }

    @Override
    public Class<WriteToolArguments> argumentType() {
        return WriteToolArguments.class;
    }

    @Override
    public String description() {
        return "Write UTF-8 content to a file, creating parent directories when needed. "
                + "Creates a missing file or overwrites an existing file.";
    }

    @Override
    public JsonNode parametersSchema() {
        return SCHEMA.deepCopy();
    }

    @Override
    public JsonNode prepareArguments(JsonNode arguments) {
        ObjectNode prepared = requireObject(arguments);
        rejectUnknownFields(prepared);
        JsonNode path = requireText(prepared, "path");
        JsonNode content = requireText(prepared, "content");
        FileToolSupport.validatePath(path.textValue());
        FileToolSupport.utf8Length(
                content.textValue(), Integer.MAX_VALUE, "content");
        return prepared;
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(
            String toolCallId,
            WriteToolArguments arguments,
            ToolUpdateSink updates,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        try {
            cancellation.throwIfCancelled();
            byte[] content = FileToolSupport.encodeUtf8(
                    arguments.content(), Integer.MAX_VALUE, "content");
            writer.write(arguments.path(), content, cancellation);
            return completed(ToolExecutionResult.success(List.of(
                    new Content.Text("Wrote " + content.length + " bytes."))));
        } catch (CancellationException e) {
            return completed(ToolExecutionResult.failure("write cancelled"));
        } catch (IOException | RuntimeException e) {
            return completed(ToolExecutionResult.failure(
                    "write failed: " + FileToolSupport.safeMessage(e)));
        }
    }

    private static ObjectNode requireObject(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("arguments must be an object");
        }
        return ((ObjectNode) arguments).deepCopy();
    }

    private static void rejectUnknownFields(ObjectNode arguments) {
        arguments.fieldNames().forEachRemaining(field -> {
            if (!ARGUMENT_FIELDS.contains(field)) {
                throw new IllegalArgumentException("unknown argument field: " + field);
            }
        });
    }

    private static JsonNode requireText(ObjectNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value;
    }

    private static Path validateWorkingDirectory(Path workingDirectory) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Path absolute = workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("workingDirectory must be an existing directory");
        }
        return absolute;
    }

    private static CompletionStage<ToolExecutionResult> completed(ToolExecutionResult result) {
        return CompletableFuture.completedFuture(result);
    }

    private static JsonNode createSchema() {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        var properties = root.putObject("properties");
        properties.putObject("path")
                .put("type", "string")
                .put("minLength", 1)
                .put("maxLength", FileToolSupport.MAX_PATH_CHARACTERS)
                .put("description", "Path to create or replace, relative to the working directory or absolute");
        properties.putObject("content")
                .put("type", "string")
                .put("description", "Complete UTF-8 file content");
        root.putArray("required").add("path").add("content");
        return root;
    }
}
