package site.pplee.jcode.codingagent.tool;

/** Arguments accepted by the local {@code ls} tool. */
public record LsToolArguments(String path, Integer limit) {
    public static final String DEFAULT_PATH = ".";
    public static final int DEFAULT_LIMIT = 1_000;

    public LsToolArguments {
        path = FileToolSupport.validatePath(path == null ? DEFAULT_PATH : path);
        limit = limit == null ? DEFAULT_LIMIT : limit;
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (limit > LsTool.MAX_ENTRIES) {
            throw new IllegalArgumentException("limit must not exceed " + LsTool.MAX_ENTRIES);
        }
    }
}
