package site.pplee.jcode.codingagent.tool;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class ToolTestSupport {
    private ToolTestSupport() {
    }

    static String text(ToolExecutionResult result) {
        return assertInstanceOf(Content.Text.class, result.content().getFirst()).text();
    }
}
