package site.pplee.jcode.ai.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnicodeSanitizerTest {
    private static final String LONE_HIGH = "\uD83D";
    private static final String LONE_LOW = "\uDC4D";
    private static final String EMOJI = "\uD83D\uDC4D";
    private static final String COMBINING = "e\u0301";

    @Test
    void removesLoneHighSurrogate() {
        assertEquals("ab", UnicodeSanitizer.removeUnpairedSurrogates("a" + LONE_HIGH + "b"));
        assertFalse(UnicodeSanitizer.isWellFormedUtf16("a" + LONE_HIGH + "b"));
    }

    @Test
    void removesLoneLowSurrogate() {
        assertEquals("ab", UnicodeSanitizer.removeUnpairedSurrogates("a" + LONE_LOW + "b"));
        assertFalse(UnicodeSanitizer.isWellFormedUtf16("a" + LONE_LOW + "b"));
    }

    @Test
    void removesRunsOfInvalidSurrogates() {
        assertEquals("ok", UnicodeSanitizer.removeUnpairedSurrogates(
                LONE_HIGH + LONE_HIGH + "ok" + LONE_LOW + LONE_LOW));
    }

    @Test
    void preservesValidEmojiAndSupplementaryPairs() {
        String value = "hi " + EMOJI + " " + "\uD83C\uDF89";
        assertSame(value, UnicodeSanitizer.removeUnpairedSurrogates(value));
        assertTrue(UnicodeSanitizer.isWellFormedUtf16(value));
    }

    @Test
    void removesInvalidSurrogatesMixedWithValidPairs() {
        assertEquals("x" + EMOJI + "y", UnicodeSanitizer.removeUnpairedSurrogates(
                "x" + LONE_HIGH + EMOJI + LONE_LOW + "y"));
    }

    @Test
    void leavesCombiningSequencesUnchanged() {
        assertSame(COMBINING, UnicodeSanitizer.removeUnpairedSurrogates(COMBINING));
        assertTrue(UnicodeSanitizer.isWellFormedUtf16(COMBINING));
    }

    @Test
    void returnsOriginalInstanceWhenAlreadyWellFormed() {
        String bmp = "plain ASCII and café";
        assertSame(bmp, UnicodeSanitizer.removeUnpairedSurrogates(bmp));
        assertSame("", UnicodeSanitizer.removeUnpairedSurrogates(""));
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> UnicodeSanitizer.removeUnpairedSurrogates(null));
        assertThrows(NullPointerException.class, () -> UnicodeSanitizer.isWellFormedUtf16(null));
    }
}
