package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.tool.GrammarSyntax;
import site.pplee.jcode.ai.tool.Requirement;
import site.pplee.jcode.ai.tool.ToolInputConstraint;
import site.pplee.jcode.ai.tool.ToolSpec;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Isolated strict-schema conversion and grammar JSON-delta tests.
 */
class OpenAiConstrainedSamplingTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void grammarJsonDeltasAreAppendOnlyAndEscapeSpecialCharacters() throws Exception {
        var buffer = new OpenAiConstrainedSampling.GrammarToolInputJsonBuffer();
        String first = buffer.append("payload", "a\"", false);
        String second = buffer.append("payload", "a\"\nb\\👍", true);
        assertEquals(MAPPER.readTree("{\"payload\":\"a\\\"\\nb\\\\👍\"}"),
                MAPPER.readTree(first + second));
        assertNull(buffer.append("payload", "a\"\nb\\👍", true));
        assertThrows(IllegalStateException.class, () -> buffer.append("payload", "changed", true));
    }

    @Test
    void grammarInputPropertiesOnlyIncludeResolvedCustomTools() throws Exception {
        var schema = MAPPER.readTree(
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}");
        var grammar = new ToolSpec("search", "s", schema,
                new ToolInputConstraint.Grammar(Map.of(GrammarSyntax.LARK, "start: /x/"), Requirement.PREFER));
        var plain = ToolSpec.minimal("echo");
        assertTrue(OpenAiConstrainedSampling.grammarInputProperties(java.util.List.of(plain, grammar), false)
                .isEmpty());
        assertEquals(Map.of("search", "query"),
                OpenAiConstrainedSampling.grammarInputProperties(java.util.List.of(plain, grammar), true));
    }

    @Test
    void grammarWireSyntaxStaysProviderLocal() {
        assertEquals("lark", OpenAiConstrainedSampling.grammarWireSyntax(GrammarSyntax.LARK));
        assertEquals("regex", OpenAiConstrainedSampling.grammarWireSyntax(GrammarSyntax.REGEX));
    }
}
