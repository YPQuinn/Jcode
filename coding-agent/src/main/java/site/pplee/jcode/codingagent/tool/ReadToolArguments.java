package site.pplee.jcode.codingagent.tool;

import java.util.Objects;

/** Arguments accepted by the local {@code read} tool. */
public record ReadToolArguments(String path, Integer offset, Integer limit) {
    public static final int DEFAULT_OFFSET = 1;
    public static final int DEFAULT_LIMIT = 2000;

    public ReadToolArguments {
        Objects.requireNonNull(path, "path must not be null");
        offset = offset == null ? DEFAULT_OFFSET : offset;
        limit = limit == null ? DEFAULT_LIMIT : limit;
        if (offset < 1) {
            throw new IllegalArgumentException("offset must be at least 1");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
    }
}
