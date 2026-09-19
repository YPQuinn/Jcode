package site.pplee.jcode.codingagent.tool;

/** Built-in coding tools that can be explicitly enabled for a session. */
public enum CodingTool {
    READ("read"),
    WRITE("write"),
    EDIT("edit"),
    BASH("bash"),
    GREP("grep"),
    FIND("find"),
    LS("ls");

    private final String toolName;

    CodingTool(String toolName) {
        this.toolName = toolName;
    }

    /** Stable name exposed to the model. */
    public String toolName() {
        return toolName;
    }
}
