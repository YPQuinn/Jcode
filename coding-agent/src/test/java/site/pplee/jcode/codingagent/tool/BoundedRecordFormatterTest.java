package site.pplee.jcode.codingagent.tool;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoundedRecordFormatterTest {
    @Test
    void preservesCompleteRecordsAcrossRecordAndByteLimits() {
        var formatter = new BoundedRecordFormatter(2, 7);

        assertEquals(BoundedRecordFormatter.AppendResult.ADDED, formatter.append("one"));
        assertEquals(BoundedRecordFormatter.AppendResult.ADDED, formatter.append("two"));
        assertEquals(BoundedRecordFormatter.AppendResult.OUTPUT_FULL, formatter.append("x"));

        assertEquals("one\ntwo", formatter.content());
        assertEquals(2, formatter.recordCount());
        assertEquals(7, formatter.utf8Bytes());
    }

    @Test
    void distinguishesOversizedRecordAndCanAcceptALaterSmallerRecord() {
        var formatter = new BoundedRecordFormatter(3, 6);

        assertEquals(BoundedRecordFormatter.AppendResult.RECORD_TOO_LARGE,
                formatter.append("1234567"));
        assertEquals(BoundedRecordFormatter.AppendResult.ADDED, formatter.append("é"));
        assertEquals(BoundedRecordFormatter.AppendResult.OUTPUT_FULL, formatter.append("four"));
        assertEquals(BoundedRecordFormatter.AppendResult.ADDED, formatter.append("x"));

        assertEquals("é\nx", formatter.content());
        assertEquals(4, formatter.content().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void rejectsMultilineAndMalformedRecords() {
        var formatter = new BoundedRecordFormatter(2, 20);

        assertThrows(IllegalArgumentException.class, () -> formatter.append("a\nb"));
        assertThrows(IllegalArgumentException.class, () -> formatter.append("a\rb"));
        assertThrows(IllegalArgumentException.class, () -> formatter.append("\ud800"));
    }

    @Test
    void appendsNoticesWithinAnIndependentBudget() {
        String output = BoundedRecordFormatter.withNotices(
                "record", List.of("first", "second", "third"), 2, 20);

        assertEquals("record\n\n[first]\n[second]", output);
    }
}
