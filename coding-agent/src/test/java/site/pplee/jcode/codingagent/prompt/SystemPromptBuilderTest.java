package site.pplee.jcode.codingagent.prompt;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.tool.ToolSpec;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SystemPromptBuilderTest {
    private static final ToolSpec READ = new ToolSpec(
            "read", "Read a bounded file.", JsonNodeFactory.instance.objectNode());

    @Test
    void buildsDefaultPromptFromActualToolsAndWorkingDirectory() {
        var cwd = Path.of("work").toAbsolutePath().normalize();
        var prompt = SystemPromptBuilder.build(cwd, List.of(READ), null, null);

        assertTrue(prompt.contains("Jcode"));
        assertTrue(prompt.contains("- read: Read a bounded file."));
        assertEquals(1, occurrences(prompt, "- read:"));
        assertTrue(prompt.endsWith("Current working directory: " + cwd));
    }

    @Test
    void customPromptReplacesDefaultButKeepsToolsAndDirectory() {
        var cwd = Path.of("work").toAbsolutePath().normalize();
        var prompt = SystemPromptBuilder.build(cwd, List.of(READ), "  custom\nbody  ", "\nappend  ");

        assertTrue(prompt.startsWith("  custom\nbody  "));
        assertFalse(prompt.contains("expert coding assistant"));
        assertTrue(prompt.contains("\nappend  "));
        assertTrue(prompt.indexOf("append") < prompt.indexOf("Available tools:"));
        assertTrue(prompt.indexOf("Available tools:") < prompt.indexOf("Current working directory:"));
    }

    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
