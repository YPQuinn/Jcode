package site.pplee.jcode.codingagent.tool;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class OutputTruncatorTest {
    @Test
    void preservesWholeLinesAndPointsContinuationAtFirstUnreturnedLine() throws Exception {
        var result = OutputTruncator.truncate(
                new StringReader("alpha\r\nbeta\rgamma\n"), 1, 2, 50 * 1024,
                new MutableCancellationSignal());

        assertEquals("alpha\nbeta\n", result.content());
        assertTrue(result.truncated());
        assertEquals(2, result.outputLines());
        assertEquals(result.content().getBytes(StandardCharsets.UTF_8).length, result.outputBytes());
        assertEquals(3, result.nextOffset().orElseThrow());
    }

    @Test
    void exactLimitAtEndOfFileDoesNotAdvertiseContinuation() throws Exception {
        var result = OutputTruncator.truncate(
                new StringReader("one\ntwo"), 1, 2, 50 * 1024,
                new MutableCancellationSignal());

        assertEquals("one\ntwo", result.content());
        assertFalse(result.truncated());
        assertTrue(result.nextOffset().isEmpty());
    }

    @Test
    void byteLimitKeepsNextCompleteLineForNextPage() throws Exception {
        var result = OutputTruncator.truncate(
                new StringReader("一\n二二\n三\n"), 1, 10, 7,
                new MutableCancellationSignal());

        assertEquals("一\n", result.content());
        assertTrue(result.truncated());
        assertEquals(2, result.nextOffset().orElseThrow());
    }

    @Test
    void countsSupplementaryCodePointsAndNormalizedLineEndingsAsUtf8Bytes() throws Exception {
        var result = OutputTruncator.truncate(
                new StringReader("😀\r\ntext"), 1, 10, 5,
                new MutableCancellationSignal());

        assertEquals("😀\n", result.content());
        assertEquals(5, result.outputBytes());
        assertTrue(result.truncated());
        assertEquals(2, result.nextOffset().orElseThrow());
    }

    @Test
    void returnsEmptyResultForEmptyInput() throws Exception {
        var result = OutputTruncator.truncate(
                new StringReader(""), 1, 10, 100, new MutableCancellationSignal());

        assertEquals("", result.content());
        assertEquals(0, result.outputLines());
        assertEquals(0, result.outputBytes());
        assertFalse(result.truncated());
    }

    @Test
    void rejectsRequestedLineThatCannotFitWithoutSplitting() {
        var longLine = "x".repeat(51 * 1024) + "\nnext\n";

        var error = assertThrows(LineTooLongException.class, () -> OutputTruncator.truncate(
                new StringReader(longLine), 1, 2000, 50 * 1024,
                new MutableCancellationSignal()));

        assertEquals(1, error.lineNumber());
        assertFalse(error.getMessage().contains(longLine));
    }

    @Test
    void stopsScanningAnOversizedFirstLineOnceItExceedsTheByteLimit() {
        int byteLimit = 50 * 1024;
        var source = new CountingReader("x".repeat(1_000_000));

        var error = assertThrows(LineTooLongException.class, () -> OutputTruncator.truncate(
                source, 1, 2000, byteLimit, new MutableCancellationSignal()));

        assertEquals(1, error.lineNumber());
        assertEquals(byteLimit + 1, source.charactersRead());
    }

    @Test
    void stopsScanningAnUnreturnedLineAtTheRemainingByteBudget() throws Exception {
        int byteLimit = 50 * 1024;
        var source = new CountingReader("ok\n" + "x".repeat(1_000_000));

        var result = OutputTruncator.truncate(
                source, 1, 2000, byteLimit, new MutableCancellationSignal());

        assertEquals("ok\n", result.content());
        assertTrue(result.truncated());
        assertEquals(2, result.nextOffset().orElseThrow());
        assertEquals(byteLimit + 1, source.charactersRead());
    }

    @Test
    void measuresRemainingBudgetInUtf8BytesWithoutSplittingSurrogatePairs() throws Exception {
        var source = new CountingReader("a\n😀" + "x".repeat(100_000));

        var result = OutputTruncator.truncate(
                source, 1, 2000, 4, new MutableCancellationSignal());

        assertEquals("a\n", result.content());
        assertTrue(result.truncated());
        assertEquals(2, result.nextOffset().orElseThrow());
        assertEquals(4, source.charactersRead());
    }

    @Test
    void skipsLongEarlierLinesWithoutUnboundedAccumulation() throws Exception {
        var input = "x".repeat(60 * 1024) + "\nvisible\n";
        var result = OutputTruncator.truncate(
                new StringReader(input), 2, 10, 50 * 1024,
                new MutableCancellationSignal());

        assertEquals("visible\n", result.content());
        assertFalse(result.truncated());
    }

    @Test
    void continuationLookaheadDoesNotScanTheUnreturnedLine() throws Exception {
        var source = new CountingReader("a\n" + "x".repeat(100_000));

        var result = OutputTruncator.truncate(
                source, 1, 1, 50 * 1024, new MutableCancellationSignal());

        assertEquals("a\n", result.content());
        assertTrue(result.truncated());
        assertEquals(3, source.charactersRead());
    }

    @Test
    void acceptsMaximumOffsetWhenEndOfFileRequiresNoContinuationArithmetic() throws Exception {
        var result = OutputTruncator.truncate(
                new StringReader("one\ntwo\n"), Integer.MAX_VALUE, Integer.MAX_VALUE, 50 * 1024,
                new MutableCancellationSignal());

        assertEquals("", result.content());
        assertFalse(result.truncated());
        assertTrue(result.nextOffset().isEmpty());
    }

    private static final class CountingReader extends StringReader {
        private int charactersRead;

        private CountingReader(String value) {
            super(value);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                charactersRead++;
            }
            return value;
        }

        int charactersRead() {
            return charactersRead;
        }
    }
}
