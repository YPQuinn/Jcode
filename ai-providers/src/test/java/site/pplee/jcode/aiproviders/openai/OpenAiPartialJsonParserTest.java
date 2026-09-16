package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class OpenAiPartialJsonParserTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode PREVIOUS = MAPPER.createObjectNode().put("kept", true);

    @Test
    void incompleteStringClosesWithoutGuessingNewFields() {
        JsonNode parsed = OpenAiPartialJsonParser.parse("{\"city\":\"Lo", PREVIOUS);
        assertEquals("Lo", parsed.get("city").asText());
        assertEquals(1, parsed.size());
    }

    @Test
    void trailingCommaIsDroppedAndObjectClosed() {
        JsonNode parsed = OpenAiPartialJsonParser.parse("{\"a\":1,", PREVIOUS);
        assertEquals(1, parsed.get("a").asInt());
        assertEquals(1, parsed.size());
    }

    @Test
    void nestedObjectsAreClosed() {
        JsonNode parsed = OpenAiPartialJsonParser.parse("{\"a\":{\"b\":1", PREVIOUS);
        assertEquals(1, parsed.get("a").get("b").asInt());
    }

    @Test
    void missingValueKeepsPrevious() {
        assertSame(PREVIOUS, OpenAiPartialJsonParser.parse("{\"a\":", PREVIOUS));
    }

    @Test
    void incompleteLiteralKeepsPrevious() {
        assertSame(PREVIOUS, OpenAiPartialJsonParser.parse("{\"a\":tru", PREVIOUS));
    }

    @Test
    void danglingEscapeKeepsPrevious() {
        assertSame(PREVIOUS, OpenAiPartialJsonParser.parse("{\"a\":\"foo\\", PREVIOUS));
    }

    @Test
    void wrongNestingKeepsPrevious() {
        assertSame(PREVIOUS, OpenAiPartialJsonParser.parse("{\"a\":[}", PREVIOUS));
    }

    @Test
    void nonObjectRootKeepsPrevious() {
        assertSame(PREVIOUS, OpenAiPartialJsonParser.parse("[1,2", PREVIOUS));
        assertSame(PREVIOUS, OpenAiPartialJsonParser.parse("\"hi", PREVIOUS));
    }

    @Test
    void completeObjectUsesStrictParse() {
        JsonNode parsed = OpenAiPartialJsonParser.parse("{\"city\":\"London\"}", PREVIOUS);
        assertEquals("London", parsed.get("city").asText());
    }
}
