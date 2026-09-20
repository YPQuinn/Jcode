package site.pplee.jcode.codingagent.tool;

import java.util.Arrays;
import java.util.Objects;

/** Small, platform-independent glob matcher for search-result paths. */
final class SearchGlob {
    static final int MAX_PATTERN_CHARACTERS = 4_096;
    static final int MAX_PATH_CHARACTERS = 4_096;
    private static final int MAX_MATCH_OPERATIONS = 1_000_000;

    private final String[] segments;
    private final boolean basenameOnly;

    private SearchGlob(String[] segments, boolean basenameOnly) {
        this.segments = segments;
        this.basenameOnly = basenameOnly;
    }

    static SearchGlob compile(String pattern) {
        Objects.requireNonNull(pattern, "glob must not be null");
        validateText(pattern, "glob", MAX_PATTERN_CHARACTERS);
        if (pattern.isEmpty()) {
            throw new IllegalArgumentException("glob must not be empty");
        }
        rejectUnsupportedSyntax(pattern);
        String[] segments = pattern.split("/", -1);
        if (Arrays.stream(segments).anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("glob must not contain empty path segments");
        }
        for (String segment : segments) {
            if (segment.contains("**") && !segment.equals("**")) {
                throw new IllegalArgumentException("** must occupy an entire path segment");
            }
        }
        return new SearchGlob(segments, segments.length == 1);
    }

    boolean matches(String path) {
        Objects.requireNonNull(path, "path must not be null");
        validateText(path, "path", MAX_PATH_CHARACTERS);
        if (path.isEmpty()) {
            return false;
        }
        String[] pathSegments = path.split("/", -1);
        if (Arrays.stream(pathSegments).anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("path must be relative and use non-empty segments");
        }
        var budget = new MatchBudget();
        if (basenameOnly) {
            return matchesSegment(segments[0], pathSegments[pathSegments.length - 1], budget);
        }
        return matchesPath(pathSegments, budget);
    }

    @Override
    public String toString() {
        return "SearchGlob[pattern=redacted]";
    }

    private boolean matchesPath(String[] pathSegments, MatchBudget budget) {
        int patternIndex = 0;
        int pathIndex = 0;
        int recursiveWildcard = -1;
        int recursivePathStart = -1;
        while (pathIndex < pathSegments.length) {
            budget.step();
            if (patternIndex < segments.length && segments[patternIndex].equals("**")) {
                recursiveWildcard = patternIndex++;
                recursivePathStart = pathIndex;
            } else if (patternIndex < segments.length
                    && matchesSegment(segments[patternIndex], pathSegments[pathIndex], budget)) {
                patternIndex++;
                pathIndex++;
            } else if (recursiveWildcard >= 0) {
                patternIndex = recursiveWildcard + 1;
                pathIndex = ++recursivePathStart;
            } else {
                return false;
            }
        }
        while (patternIndex < segments.length && segments[patternIndex].equals("**")) {
            budget.step();
            patternIndex++;
        }
        return patternIndex == segments.length;
    }

    private static boolean matchesSegment(String pattern, String value, MatchBudget budget) {
        int[] patternCodePoints = pattern.codePoints().toArray();
        int[] valueCodePoints = value.codePoints().toArray();
        int patternIndex = 0;
        int valueIndex = 0;
        int wildcard = -1;
        int wildcardValueStart = -1;
        while (valueIndex < valueCodePoints.length) {
            budget.step();
            if (patternIndex < patternCodePoints.length
                    && patternCodePoints[patternIndex] == '*') {
                wildcard = patternIndex++;
                wildcardValueStart = valueIndex;
            } else if (patternIndex < patternCodePoints.length
                    && (patternCodePoints[patternIndex] == '?'
                    || patternCodePoints[patternIndex] == valueCodePoints[valueIndex])) {
                patternIndex++;
                valueIndex++;
            } else if (wildcard >= 0) {
                patternIndex = wildcard + 1;
                valueIndex = ++wildcardValueStart;
            } else {
                return false;
            }
        }
        while (patternIndex < patternCodePoints.length
                && patternCodePoints[patternIndex] == '*') {
            budget.step();
            patternIndex++;
        }
        return patternIndex == patternCodePoints.length;
    }

    private static void rejectUnsupportedSyntax(String pattern) {
        if (pattern.indexOf('{') >= 0 || pattern.indexOf('}') >= 0) {
            throw new IllegalArgumentException("brace expansion is not supported");
        }
        if (pattern.indexOf('[') >= 0 || pattern.indexOf(']') >= 0) {
            throw new IllegalArgumentException("character groups are not supported");
        }
        if (pattern.startsWith("!")) {
            throw new IllegalArgumentException("negated glob rules are not supported");
        }
        for (String prefix : new String[]{"@(", "+(", "?(", "*(", "!("}) {
            if (pattern.contains(prefix)) {
                throw new IllegalArgumentException("extended glob syntax is not supported");
            }
        }
    }

    private static void validateText(String value, String label, int maximumCharacters) {
        if (value.length() > maximumCharacters) {
            throw new IllegalArgumentException(label + " exceeds the length limit");
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " must not contain NUL");
        }
        FileToolSupport.utf8Length(value, Integer.MAX_VALUE, label);
    }

    private static final class MatchBudget {
        private int operations;

        void step() {
            if (++operations > MAX_MATCH_OPERATIONS) {
                throw new IllegalArgumentException("glob match exceeds the complexity limit");
            }
        }
    }
}
