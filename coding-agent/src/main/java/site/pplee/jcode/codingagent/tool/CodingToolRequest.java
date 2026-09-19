package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable product snapshot describing one prepared tool invocation. */
public record CodingToolRequest(
        String toolCallId,
        String toolName,
        JsonNode preparedArguments,
        Path workingDirectory
) {
    public CodingToolRequest {
        Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        preparedArguments = Objects.requireNonNull(
                preparedArguments, "preparedArguments must not be null").deepCopy();
        workingDirectory = Objects.requireNonNull(
                workingDirectory, "workingDirectory must not be null")
                .toAbsolutePath().normalize();
    }

    /** Return a recursive copy so observers cannot mutate the authorization snapshot. */
    @Override
    public JsonNode preparedArguments() {
        return preparedArguments.deepCopy();
    }

    /** Avoid exposing file contents, paths, commands, or other arguments in diagnostics. */
    @Override
    public String toString() {
        return "CodingToolRequest[toolCallId=" + toolCallId
                + ", toolName=" + toolName
                + ", preparedArguments=redacted"
                + ", workingDirectory=redacted]";
    }
}
