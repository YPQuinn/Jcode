package site.pplee.jcode.aiproviders.openai;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure SSE line-parsing tests: event/data dispatch, multi-line data,
 * comments, EOF flush, and field-without-colon handling.
 */
class OpenAiSseParserTest {

    private record Event(String name, String data) {
    }

    private List<Event> parse(String sse) throws Exception {
        var events = new ArrayList<Event>();
        var parser = new OpenAiSseParser();
        parser.parse(new BufferedReader(new StringReader(sse)),
                (name, data) -> events.add(new Event(name, data)));
        return events;
    }

    @Test
    void dispatchesEventAndData() throws Exception {
        var events = parse("event: response.output_text.delta\ndata: {\"delta\":\"hi\"}\n\n");
        assertEquals(1, events.size());
        assertEquals("response.output_text.delta", events.get(0).name());
        assertEquals("{\"delta\":\"hi\"}", events.get(0).data());
    }

    @Test
    void joinsMultiLineDataWithNewline() throws Exception {
        var events = parse("data: line1\ndata: line2\n\n");
        assertEquals(1, events.size());
        assertEquals("message", events.get(0).name());
        assertEquals("line1\nline2", events.get(0).data());
    }

    @Test
    void ignoresCommentsAndKeepsMultipleEvents() throws Exception {
        var events = parse(": heartbeat\n\nevent: a\ndata: 1\n\nevent: b\ndata: 2\n\n");
        assertEquals(2, events.size());
        assertEquals("a", events.get(0).name());
        assertEquals("1", events.get(0).data());
        assertEquals("b", events.get(1).name());
        assertEquals("2", events.get(1).data());
    }

    @Test
    void flushesPendingEventAtEofWithoutBlankLine() throws Exception {
        var events = parse("data: tail");
        assertEquals(1, events.size());
        assertEquals("tail", events.get(0).data());
    }

    @Test
    void fieldWithoutColonYieldsEmptyValue() throws Exception {
        var events = parse("data\n\n");
        assertEquals(1, events.size());
        assertEquals("", events.get(0).data());
    }

    @Test
    void doneMarkerArrivesAsRegularData() throws Exception {
        var events = parse("data: [DONE]\n\n");
        assertEquals(1, events.size());
        assertEquals("[DONE]", events.get(0).data());
    }

    @Test
    void dataWithNoEventNameDefaultsToMessage() throws Exception {
        var events = parse("data: x\n\n");
        assertEquals("message", events.get(0).name());
    }
}
