package site.pplee.jcode.codingagent.tool;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoundedByteRecordReaderTest {
    @Test
    void splitsAcrossChunksWithoutRetainingDelimiters() {
        var records = new ArrayList<String>();
        var reader = reader((byte) 0, 10, 10, records);

        reader.append("one\0tw".getBytes(StandardCharsets.UTF_8), 0, 6);
        reader.append("o\0".getBytes(StandardCharsets.UTF_8), 0, 2);
        reader.finish(false);

        assertEquals(List.of("one", "two"), records);
        assertEquals(2, reader.recordCount());
    }

    @Test
    void discardsOversizedRecordAndContinuesAtTheNextDelimiter() {
        var records = new ArrayList<String>();
        var reader = reader((byte) '\n', 3, 10, records);
        byte[] input = "toolong\nok\n".getBytes(StandardCharsets.UTF_8);

        reader.append(input, 0, input.length);
        reader.finish(true);

        assertEquals(List.of("ok"), records);
        assertEquals(1, reader.oversizedRecords());
        assertEquals(2, reader.recordCount());
    }

    @Test
    void enforcesScanLimitWithoutDeliveringTheExtraRecord() {
        var records = new ArrayList<String>();
        var reader = reader((byte) '\n', 10, 2, records);
        byte[] input = "a\nb\nc\nd\n".getBytes(StandardCharsets.UTF_8);

        reader.append(input, 0, input.length);

        assertEquals(List.of("a", "b"), records);
        assertEquals(2, reader.recordCount());
        assertTrue(reader.scanLimitReached());
    }

    @Test
    void nulProtocolRejectsUnterminatedFinalRecordWhileJsonMayAcceptIt() {
        var nul = reader((byte) 0, 10, 2, new ArrayList<>());
        nul.append(new byte[]{'x'}, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> nul.finish(false));

        var lines = new ArrayList<String>();
        var newline = reader((byte) '\n', 10, 2, lines);
        newline.append(new byte[]{'x'}, 0, 1);
        newline.finish(true);
        assertEquals(List.of("x"), lines);
    }

    private static BoundedByteRecordReader reader(
            byte delimiter,
            int maximumBytes,
            int maximumRecords,
            List<String> output
    ) {
        return new BoundedByteRecordReader(
                delimiter, maximumBytes, maximumRecords,
                bytes -> output.add(new String(bytes, StandardCharsets.UTF_8)));
    }
}
