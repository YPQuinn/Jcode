package site.pplee.jcode.codingagent.tool;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EditPlannerTest {
    private final EditPlanner planner = new EditPlanner();

    @Test
    void appliesDisjointEditsAgainstOriginalContent() {
        var original = bytes("alpha\nbeta\ngamma\n");

        var plan = planner.plan(original, List.of(
                new EditReplacement("alpha", "A"),
                new EditReplacement("gamma", "G")));

        assertTrue(plan.changed());
        assertEquals(2, plan.replacementCount());
        assertEquals(1, plan.firstChangedLine());
        assertArrayEquals(bytes("A\nbeta\nG\n"), plan.finalBytes());
    }

    @Test
    void matchesCrLfAndCrAsLfWithoutRewritingUntouchedBytes() {
        var original = bytes("one\r\ntwo\rthree\nfour\r\n");

        var plan = planner.plan(original, List.of(
                new EditReplacement("two\nthree", "T\nU")));

        assertArrayEquals(bytes("one\r\nT\rU\nfour\r\n"), plan.finalBytes());
        assertEquals(2, plan.firstChangedLine());
    }

    @Test
    void usesFirstNewlineInRegionThenFirstFileNewlineForReplacement() {
        var original = bytes("head\r\nleft\nright\r\ntail");

        var regional = planner.plan(original, List.of(
                new EditReplacement("left\nright", "x\ny")));
        var fileFallback = planner.plan(original, List.of(
                new EditReplacement("tail", "x\ny")));

        assertArrayEquals(bytes("head\r\nx\ny\r\ntail"), regional.finalBytes());
        assertArrayEquals(bytes("head\r\nleft\nright\r\nx\r\ny"), fileFallback.finalBytes());
    }

    @Test
    void preservesUtf8BomAndDoesNotExposeItToMatching() {
        byte[] text = bytes("first\nsecond\n");
        byte[] original = new byte[text.length + 3];
        original[0] = (byte) 0xef;
        original[1] = (byte) 0xbb;
        original[2] = (byte) 0xbf;
        System.arraycopy(text, 0, original, 3, text.length);

        var plan = planner.plan(original, List.of(
                new EditReplacement("first", "changed")));

        assertEquals((byte) 0xef, plan.finalBytes()[0]);
        assertEquals((byte) 0xbb, plan.finalBytes()[1]);
        assertEquals((byte) 0xbf, plan.finalBytes()[2]);
        assertEquals("changed\nsecond\n",
                new String(plan.finalBytes(), 3, plan.finalBytes().length - 3,
                        StandardCharsets.UTF_8));
    }

    @Test
    void detectsMissingDuplicateOverlappingAndIncrementalMatches() {
        assertFailure("not found", "abc", List.of(new EditReplacement("missing", "x")));
        assertFailure("more than once", "aaa", List.of(new EditReplacement("aa", "x")));
        assertFailure("overlap", "abcdef", List.of(
                new EditReplacement("abcd", "x"),
                new EditReplacement("cdef", "y")));
        assertFailure("not found", "before", List.of(
                new EditReplacement("before", "after"),
                new EditReplacement("after", "done")));
    }

    @Test
    void acceptsAdjacentRangesAndReportsNoChangeWithoutMutation() {
        var adjacent = planner.plan(bytes("abcd"), List.of(
                new EditReplacement("ab", "AB"),
                new EditReplacement("cd", "CD")));
        var noChange = planner.plan(bytes("same\n"), List.of(
                new EditReplacement("same", "same")));

        assertArrayEquals(bytes("ABCD"), adjacent.finalBytes());
        assertFalse(noChange.changed());
        assertArrayEquals(bytes("same\n"), noChange.finalBytes());
    }

    @Test
    void rejectsInvalidUtf8InvalidTextAndLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(new byte[] {(byte) 0xc3, 0x28},
                        List.of(new EditReplacement("x", "y"))));
        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(bytes("x"), List.of(new EditReplacement("", "y"))));
        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(bytes("x"),
                        List.of(new EditReplacement("x", "\ud800"))));
        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(bytes("x"), java.util.stream.IntStream.range(0, 101)
                        .mapToObj(index -> new EditReplacement("x", "y"))
                        .toList()));
    }

    @Test
    void exactMatchWinsOverNormalizationAliases() {
        var plan = planner.plan(bytes("name\nｎａｍｅ\n"), List.of(new EditReplacement("name", "exact")));
        assertArrayEquals(bytes("exact\nｎａｍｅ\n"), plan.finalBytes());
    }

    @Test
    void normalizationRejectsAmbiguousOrOverlappingMatches() {
        assertFailure("more than once", "Ａ\nＡ\n", List.of(new EditReplacement("A", "x")));
        assertFailure("overlap", "ＡＢＣＤ", List.of(
                new EditReplacement("ABC", "x"), new EditReplacement("BCD", "y")));
    }

    @Test
    void normalizedEditsOnOneLineShareOneOriginalLineOverlay() {
        var plan = planner.plan(bytes("keep   \nＡＢ — ＣＤ\nkeep　\n"), List.of(
                new EditReplacement("AB", "left"), new EditReplacement("- CD", "right")));
        assertArrayEquals(bytes("keep   \nleft right\nkeep　\n"), plan.finalBytes());
    }

    @Test
    void normalizationExpansionCanReplacePartOfACompatibilityCharacter() {
        var plan = planner.plan(bytes("ﬃ"), List.of(new EditReplacement("ff", "x")));
        assertArrayEquals(bytes("xi"), plan.finalBytes());
    }

    @Test
    void normalizedLineInsertionDoesNotShiftSubsequentOriginalRanges() {
        var plan = planner.plan(bytes("Ａ\nkeep  \nＣ\n"), List.of(
                new EditReplacement("A", "one\ntwo"), new EditReplacement("C", "last")));
        assertArrayEquals(bytes("one\ntwo\nkeep  \nlast\n"), plan.finalBytes());
    }

    @Test
    void normalizingALongWhitespaceRunPreservesIndentationAndUntouchedTail() {
        String indentation = " ".repeat(100_000);
        var plan = planner.plan(bytes(indentation + "“target”\nafter  "),
                List.of(new EditReplacement("\"target\"", "changed")));
        assertArrayEquals(bytes(indentation + "changed\nafter  "), plan.finalBytes());
    }

    private void assertFailure(String message, String original, List<EditReplacement> edits) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> planner.plan(bytes(original), edits));
        assertTrue(failure.getMessage().contains(message), failure::getMessage);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
