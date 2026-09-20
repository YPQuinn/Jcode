package site.pplee.jcode.codingagent.tool;

/** Arguments accepted by the local {@code find} tool. */
public record FindToolArguments(String pattern, String path, Integer limit) {
    public static final String DEFAULT_PATH = ".";
    public static final int DEFAULT_LIMIT = 1_000;

    public FindToolArguments {
        pattern = SearchToolSupport.validateRequiredPattern(pattern);
        path = FileToolSupport.validatePath(path == null ? DEFAULT_PATH : path);
        limit = limit == null ? DEFAULT_LIMIT : limit;
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (limit > FindTool.MAX_FILES) {
            throw new IllegalArgumentException("limit must not exceed " + FindTool.MAX_FILES);
        }
    }

    @Override
    public String toString() {
        return "FindToolArguments[pattern=redacted, path=redacted, limit=" + limit + ']';
    }
}
