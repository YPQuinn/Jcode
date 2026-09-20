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

/** Atomic exact-replacement editor exposed as the {@code edit} agent tool. */
public final class EditTool implements AgentTool<EditToolArguments> {
    private static final Set<String> ARGUMENT_FIELDS = Set.of("path", "edits");
    private static final Set<String> REPLACEMENT_FIELDS = Set.of("oldText", "newText");
    private static final JsonNode SCHEMA = createSchema();

    private final FileMutationWriter writer;
    private final EditPlanner planner;

    public EditTool(Path workingDirectory) {
        this(workingDirectory, NioFileMutationOperations.INSTANCE);
    }

    EditTool(Path workingDirectory, FileMutationOperations operations) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Path absolute = workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("workingDirectory must be an existing directory");
        }
        this.writer = new FileMutationWriter(absolute, operations);
        this.planner = new EditPlanner();
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public Class<EditToolArguments> argumentType() {
        return EditToolArguments.class;
    }

    @Override
    public String description() {
        return "Edit one existing UTF-8 file with 1 to 100 exact, unique, non-overlapping replacements. "
                + "Every oldText is matched against the original file. CRLF, CR, and LF match as LF; "
                + "newText uses the replaced region's first newline style, then the file's first style. "
                + "The original and final file are each limited to 8 MiB and commit uses an atomic move.";
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
        rejectUnknownFields(prepared, ARGUMENT_FIELDS, "argument");
        JsonNode path = requireText(prepared, "path");
        FileToolSupport.validatePath(path.textValue());
        JsonNode edits = prepared.get("edits");
        if (edits == null || !edits.isArray()) {
            throw new IllegalArgumentException("edits must be an array");
        }
        if (edits.isEmpty() || edits.size() > EditToolArguments.MAX_REPLACEMENTS) {
            throw new IllegalArgumentException("edits must contain between 1 and 100 replacements");
        }

        int remainingBytes = FileMutationWriter.MAX_FILE_BYTES;
        for (int index = 0; index < edits.size(); index++) {
            JsonNode edit = edits.get(index);
            if (!edit.isObject()) {
                throw new IllegalArgumentException("edits[" + index + "] must be an object");
            }
            var editObject = (ObjectNode) edit;
            rejectUnknownFields(editObject, REPLACEMENT_FIELDS,
                    "edits[" + index + "]");
            String oldText = requireText(editObject, "oldText").textValue();
            String newText = requireText(editObject, "newText").textValue();
            if (oldText.isEmpty()) {
                throw new IllegalArgumentException("edits[" + index + "].oldText must not be empty");
            }
            int oldBytes = FileToolSupport.utf8Length(oldText, remainingBytes, "edit text");
            remainingBytes -= oldBytes;
            int newBytes = FileToolSupport.utf8Length(newText, remainingBytes, "edit text");
            remainingBytes -= newBytes;
        }
        return prepared;
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public CompletionStage<ToolExecutionResult> execute(
            String toolCallId,
            EditToolArguments arguments,
            ToolUpdateSink updates,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        try {
            cancellation.throwIfCancelled();
            var snapshot = writer.readExisting(arguments.path(), cancellation);
            var plan = planner.plan(snapshot.originalBytes(), arguments.edits());
            cancellation.throwIfCancelled();
            if (!plan.changed()) {
                return completed(ToolExecutionResult.success(List.of(
                        new Content.Text("No changes were needed."))));
            }
            writer.commit(snapshot, plan.finalBytes(), cancellation);
            return completed(ToolExecutionResult.success(List.of(new Content.Text(
                    "Applied " + plan.replacementCount() + " replacements; first changed line "
                            + plan.firstChangedLine() + '.'))));
        } catch (CancellationException e) {
            return completed(ToolExecutionResult.failure("edit cancelled"));
        } catch (IOException | RuntimeException e) {
            return completed(ToolExecutionResult.failure(
                    "edit failed: " + FileToolSupport.safeMessage(e)));
        }
    }

    private static void rejectUnknownFields(
            ObjectNode object,
            Set<String> allowed,
            String location
    ) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(
                        "unknown " + location + " field: " + field);
            }
        });
    }

    private static JsonNode requireText(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value;
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
                .put("description", "Existing UTF-8 file to edit, relative to the working directory or absolute");
        var edits = properties.putObject("edits");
        edits.put("type", "array");
        edits.put("minItems", 1);
        edits.put("maxItems", EditToolArguments.MAX_REPLACEMENTS);
        edits.put("description", "Exact replacements, all matched against the original file");
        var item = edits.putObject("items");
        item.put("type", "object");
        item.put("additionalProperties", false);
        var itemProperties = item.putObject("properties");
        itemProperties.putObject("oldText")
                .put("type", "string")
                .put("minLength", 1)
                .put("description", "Unique exact text in the original file; newline forms are equivalent");
        itemProperties.putObject("newText")
                .put("type", "string")
                .put("description", "Replacement text; newline style follows the replaced region or file");
        item.putArray("required").add("oldText").add("newText");
        root.putArray("required").add("path").add("edits");
        return root;
    }
}
