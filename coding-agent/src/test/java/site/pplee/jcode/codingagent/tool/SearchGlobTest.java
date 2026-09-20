package site.pplee.jcode.codingagent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SearchGlobTest {
    @Test
    void basenamePatternMatchesAtAnyDepthAndRemainsCaseSensitive() {
        var glob = SearchGlob.compile("*.java");

        assertTrue(glob.matches("Main.java"));
        assertTrue(glob.matches("src/main/Main.java"));
        assertFalse(glob.matches("src/main/Main.Java"));
        assertFalse(glob.matches("src/main/Main.kt"));
    }

    @Test
    void slashPatternMatchesRelativePathAndRecursiveWildcardMayMatchZeroSegments() {
        var direct = SearchGlob.compile("src/*.java");
        var recursive = SearchGlob.compile("src/**/*.java");

        assertTrue(direct.matches("src/Main.java"));
        assertFalse(direct.matches("src/main/Main.java"));
        assertTrue(recursive.matches("src/Main.java"));
        assertTrue(recursive.matches("src/main/java/Main.java"));
        assertFalse(recursive.matches("other/Main.java"));
    }

    @Test
    void questionMarkMatchesOneUnicodeCodePointAndOnlyForwardSlashSeparatesSegments() {
        var glob = SearchGlob.compile("src/?.txt");

        assertTrue(glob.matches("src/😀.txt"));
        assertFalse(glob.matches("src\\x.txt"));
        assertFalse(glob.matches("src/ab.txt"));
    }

    @Test
    void supportsMultipleRecursiveSegmentsWithoutRegexBacktracking() {
        var glob = SearchGlob.compile("**/generated/**/Test?.java");

        assertTrue(glob.matches("generated/Test1.java"));
        assertTrue(glob.matches("a/generated/x/y/TestA.java"));
        assertFalse(glob.matches("a/generated/x/TestLong.java"));
    }

    @Test
    void wildcardStillMatchesWhenTheFileNameContainsLiteralStars() {
        for (String path : new String[]{"*report.txt", "a*b.txt", "dir/*report.txt"}) {
            assertTrue(SearchGlob.compile("*").matches(path), path);
            assertTrue(SearchGlob.compile("*.txt").matches(path), path);
        }
        assertTrue(SearchGlob.compile("a*b.txt").matches("a*longb.txt"));
        assertFalse(SearchGlob.compile("*.java").matches("*report.txt"));
    }

    @Test
    void rejectsUnsupportedOrAmbiguousSyntax() {
        for (String pattern : new String[]{
                "{a,b}.java", "[ab].java", "!secret", "@(a).java",
                "src/**x/file", "/absolute", "src//file", "src/"
        }) {
            assertThrows(IllegalArgumentException.class, () -> SearchGlob.compile(pattern), pattern);
        }
    }

    @Test
    void validatesPatternAndCandidateBoundsWithoutEchoingInputs() {
        assertThrows(IllegalArgumentException.class, () -> SearchGlob.compile(""));
        assertThrows(IllegalArgumentException.class, () -> SearchGlob.compile("a\0b"));
        assertThrows(IllegalArgumentException.class, () -> SearchGlob.compile("\ud800"));
        assertThrows(IllegalArgumentException.class,
                () -> SearchGlob.compile("x".repeat(SearchGlob.MAX_PATTERN_CHARACTERS + 1)));

        var glob = SearchGlob.compile("*.java");
        assertThrows(IllegalArgumentException.class, () -> glob.matches("/absolute.java"));
        assertThrows(IllegalArgumentException.class,
                () -> glob.matches("x".repeat(SearchGlob.MAX_PATH_CHARACTERS + 1)));
        assertFalse(glob.toString().contains("*.java"));
    }
}
