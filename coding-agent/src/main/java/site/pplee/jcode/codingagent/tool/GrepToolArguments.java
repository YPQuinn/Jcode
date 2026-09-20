package site.pplee.jcode.codingagent.tool;

/** Arguments accepted by the local {@code grep} tool. */
public record GrepToolArguments(
        String pattern,
        String path,
        String glob,
        Boolean ignoreCase,
        Boolean literal,
        Integer limit
) {
    public static final String DEFAULT_PATH = ".";
    public static final int DEFAULT_LIMIT = 100;

    public GrepToolArguments {
        pattern = SearchToolSupport.validateRequiredPattern(pattern);
        path = FileToolSupport.validatePath(path == null ? DEFAULT_PATH : path);
        glob = SearchToolSupport.validateOptionalGlob(glob);
        ignoreCase = ignoreCase == null ? Boolean.FALSE : ignoreCase;
        literal = literal == null ? Boolean.FALSE : literal;
        limit = limit == null ? DEFAULT_LIMIT : limit;
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (limit > GrepTool.MAX_MATCHES) {
            throw new IllegalArgumentException("limit must not exceed " + GrepTool.MAX_MATCHES);
        }
    }

    @Override
    public String toString() {
        return "GrepToolArguments[pattern=redacted, path=redacted, glob="
                + (glob == null ? "absent" : "redacted")
                + ", ignoreCase=" + ignoreCase
                + ", literal=" + literal
                + ", limit=" + limit + ']';
    }
}
