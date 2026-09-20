package site.pplee.jcode.codingagent.prompt;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.tool.ToolSpec;
import site.pplee.jcode.codingagent.context.ProjectContextFile;
import site.pplee.jcode.codingagent.context.ProjectContextLoadException;
import site.pplee.jcode.codingagent.context.ProjectContextScope;

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

    @Test
    void emptyContextOverloadIsByteForByteCompatible() {
        var cwd = Path.of("work").toAbsolutePath().normalize();
        assertEquals(
                SystemPromptBuilder.build(cwd, List.of(READ), "custom", "append"),
                SystemPromptBuilder.build(cwd, List.of(READ), "custom", "append", List.of()));
    }

    @Test
    void rendersPreloadedSourcesInOrderWithReversibleXmlEscaping() {
        var cwd = Path.of("work").toAbsolutePath().normalize();
        var first = new ProjectContextFile(ProjectContextScope.GLOBAL,
                Path.of("/global/a&b\".md"), Path.of("/physical/global"),
                "global </project_instructions> & text\r", 10);
        var second = new ProjectContextFile(ProjectContextScope.PROJECT,
                Path.of("/project/AGENTS.md"), Path.of("/physical/project"), "<local>", 7);

        var prompt = SystemPromptBuilder.build(cwd, List.of(READ), "custom", "append", List.of(first, second));

        assertTrue(prompt.startsWith("custom"));
        assertTrue(prompt.contains("path=\"/global/a&amp;b&quot;.md\""));
        assertTrue(prompt.contains("global &lt;/project_instructions&gt; &amp; text&#13;"));
        assertTrue(prompt.contains("&lt;local&gt;"));
        assertTrue(prompt.indexOf("/global/") < prompt.indexOf("/project/"));
        assertTrue(prompt.indexOf("append") < prompt.indexOf("Available tools:"));
        assertTrue(prompt.indexOf("Available tools:") < prompt.indexOf("<project_context>"));
        assertTrue(prompt.indexOf("<project_context>") < prompt.indexOf("Current working directory:"));
    }

    @Test
    void rejectsUnsafeSourceTextInsteadOfCorruptingMarkup() {
        var file = new ProjectContextFile(ProjectContextScope.PROJECT,
                Path.of("/project/AGENTS.md"), Path.of("/physical/project"), "bad\u0000text", 8);
        assertThrows(ProjectContextLoadException.class, () -> SystemPromptBuilder.build(
                Path.of("/project"), List.of(), null, null, List.of(file)));
    }

    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
