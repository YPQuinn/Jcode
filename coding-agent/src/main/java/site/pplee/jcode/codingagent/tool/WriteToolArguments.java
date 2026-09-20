package site.pplee.jcode.codingagent.tool;

import java.util.Objects;

/** Arguments accepted by the local {@code write} tool. */
public record WriteToolArguments(String path, String content) {
    public WriteToolArguments {
        path = FileToolSupport.validatePath(path);
        Objects.requireNonNull(content, "content must not be null");
    }
}
