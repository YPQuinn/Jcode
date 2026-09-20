package site.pplee.jcode.codingagent.tool;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Plans exact-first replacements with a normalized fallback and unchanged-line preservation. */
final class EditPlanner {
    private static final byte[] UTF8_BOM = {
            (byte) 0xef, (byte) 0xbb, (byte) 0xbf
    };

    /** Match every replacement against the same original byte sequence. */
    EditPlan plan(byte[] originalBytes, List<EditReplacement> replacements) {
        Objects.requireNonNull(originalBytes, "originalBytes must not be null");
        Objects.requireNonNull(replacements, "replacements must not be null");
        if (originalBytes.length > LocalFileAccess.MAX_EDIT_BYTES) {
            throw new IllegalArgumentException("original file exceeds the 8 MiB limit");
        }
        if (replacements.isEmpty()) {
            throw new IllegalArgumentException("edits must contain at least one replacement");
        }
        if (replacements.size() > EditToolArguments.MAX_REPLACEMENTS) {
            throw new IllegalArgumentException("edits exceeds the 100 replacement limit");
        }

        validateReplacementBudget(replacements);
        boolean hasBom = startsWithBom(originalBytes);
        int contentOffset = hasBom ? UTF8_BOM.length : 0;
        String original = decodeUtf8(originalBytes, contentOffset);
        var normalized = normalizeWithMapping(original);
        boolean fuzzy = replacements.stream().anyMatch(
                replacement -> !normalized.text().contains(normalizeNewlines(replacement.oldText())));
        String matchingText = fuzzy ? normalizeForMatch(normalized.text()) : normalized.text();
        String fileNewline = firstNewline(original);
        var ranges = new ArrayList<PlannedReplacement>(replacements.size());

        for (int index = 0; index < replacements.size(); index++) {
            EditReplacement replacement = Objects.requireNonNull(
                    replacements.get(index), "edits must not contain null");
            String oldText = normalizeNewlines(replacement.oldText());
            if (fuzzy) {
                oldText = normalizeForMatch(oldText);
            }
            if (oldText.isEmpty()) {
                throw new IllegalArgumentException("oldText must not be empty");
            }
            int match = matchingText.indexOf(oldText);
            if (match < 0) {
                throw new IllegalArgumentException("edit " + (index + 1) + " oldText was not found");
            }
            if (matchingText.indexOf(oldText, match + 1) >= 0) {
                throw new IllegalArgumentException(
                        "edit " + (index + 1) + " oldText occurs more than once");
            }
            if (fuzzy) {
                ranges.add(new PlannedReplacement(match, match + oldText.length(),
                        normalizeNewlines(replacement.newText())));
                continue;
            }
            int rawStart = normalized.rawOffsets()[match];
            int rawEnd = normalized.rawOffsets()[match + oldText.length()];
            String newline = firstNewline(original.substring(rawStart, rawEnd));
            if (newline == null) {
                newline = fileNewline == null ? "\n" : fileNewline;
            }
            String replacementText = restoreNewlines(
                    normalizeNewlines(replacement.newText()), newline);
            ranges.add(new PlannedReplacement(rawStart, rawEnd, replacementText));
        }

        ranges.sort(Comparator.comparingInt(PlannedReplacement::start));
        for (int index = 1; index < ranges.size(); index++) {
            PlannedReplacement previous = ranges.get(index - 1);
            PlannedReplacement current = ranges.get(index);
            if (current.start() < previous.end()) {
                throw new IllegalArgumentException("edit ranges overlap");
            }
        }

        if (fuzzy) {
            ranges = projectTouchedLines(original, normalized, matchingText, ranges);
        }
        var result = new StringBuilder(original.length());
        int cursor = 0;
        for (var range : ranges) {
            result.append(original, cursor, range.start());
            result.append(range.replacement());
            cursor = range.end();
        }
        result.append(original, cursor, original.length());

        int bomBytes = hasBom ? UTF8_BOM.length : 0;
        byte[] encoded = FileToolSupport.encodeUtf8(
                result.toString(), LocalFileAccess.MAX_EDIT_BYTES - bomBytes, "edit result");
        byte[] finalBytes = hasBom ? withBom(encoded) : encoded;
        int firstLine = lineNumberAt(original, ranges.getFirst().start());
        return new EditPlan(
                finalBytes,
                replacements.size(),
                firstLine,
                !Arrays.equals(originalBytes, finalBytes));
    }

    /** Normalize matching text, never the untouched output lines. */
    private static String normalizeForMatch(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        var result = new StringBuilder(normalized.length());
        for (int start = 0; start < normalized.length();) {
            int newline = normalized.indexOf('\n', start);
            int end = newline < 0 ? normalized.length() : newline;
            int trimmedEnd = end;
            while (trimmedEnd > start && isTrailingWhitespace(normalized.charAt(trimmedEnd - 1))) {
                trimmedEnd--;
            }
            for (int index = start; index < trimmedEnd; index++) {
                char value = normalized.charAt(index);
                result.append(switch (value) {
                    case '\u2018', '\u2019', '\u201a', '\u201b' -> '\'';
                    case '\u201c', '\u201d', '\u201e', '\u201f' -> '"';
                    case '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-';
                    case '\u00a0', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007',
                         '\u2008', '\u2009', '\u200a', '\u202f', '\u205f', '\u3000' -> ' ';
                    default -> value;
                });
            }
            if (newline >= 0) {
                result.append('\n');
            }
            start = end + 1;
        }
        return result.toString();
    }

    private static boolean isTrailingWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\f' || value == '\u000b'
                || value == '\r' || value == '\u00a0' || value == '\u1680'
                || value >= '\u2000' && value <= '\u200a'
                || value == '\u2028' || value == '\u2029' || value == '\u202f'
                || value == '\u205f' || value == '\u3000' || value == '\ufeff';
    }

    /** Overlay normalized edits by their actual line ranges, not by a diff alignment. */
    private static ArrayList<PlannedReplacement> projectTouchedLines(
            String original, NormalizedText normalized, String matchingText,
            List<PlannedReplacement> edits
    ) {
        int[] originalLines = lineStarts(normalized.text());
        int[] matchingLines = lineStarts(matchingText);
        if (originalLines.length != matchingLines.length) {
            throw new IllegalArgumentException("normalization changed the number of lines");
        }
        var projected = new ArrayList<PlannedReplacement>();
        for (int index = 0; index < edits.size();) {
            int first = index;
            int startLine = containingLine(matchingLines, edits.get(index).start());
            int endLine = containingLine(matchingLines, edits.get(index).end() - 1);
            while (++index < edits.size()
                    && containingLine(matchingLines, edits.get(index).start()) <= endLine) {
                endLine = Math.max(endLine, containingLine(matchingLines, edits.get(index).end() - 1));
            }
            int start = matchingLines[startLine];
            int end = matchingLines[endLine + 1];
            var block = new StringBuilder(matchingText.substring(start, end));
            for (int editIndex = index - 1; editIndex >= first; editIndex--) {
                var edit = edits.get(editIndex);
                block.replace(edit.start() - start, edit.end() - start, edit.replacement());
            }
            int rawStart = normalized.rawOffsets()[originalLines[startLine]];
            int rawEnd = normalized.rawOffsets()[originalLines[endLine + 1]];
            String newline = firstNewline(original.substring(rawStart, rawEnd));
            if (newline == null) {
                newline = firstNewline(original);
            }
            projected.add(new PlannedReplacement(rawStart, rawEnd,
                    restoreNewlines(block.toString(), newline == null ? "\n" : newline)));
        }
        return projected;
    }

    /** Include a terminal sentinel so a line's end is the following start. */
    private static int[] lineStarts(String text) {
        int count = 1;
        for (int index = 0; index + 1 < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                count++;
            }
        }
        var starts = new int[count + 1];
        int line = 1;
        for (int index = 0; index + 1 < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                starts[line++] = index + 1;
            }
        }
        starts[count] = text.length();
        return starts;
    }

    private static int containingLine(int[] starts, int offset) {
        int found = Arrays.binarySearch(starts, offset);
        return found >= 0 ? found : -found - 2;
    }

    private static void validateReplacementBudget(List<EditReplacement> replacements) {
        int remaining = LocalFileAccess.MAX_EDIT_BYTES;
        for (int index = 0; index < replacements.size(); index++) {
            EditReplacement replacement = Objects.requireNonNull(
                    replacements.get(index), "edits must not contain null");
            int oldBytes = FileToolSupport.utf8Length(
                    replacement.oldText(), remaining, "edit text");
            remaining -= oldBytes;
            int newBytes = FileToolSupport.utf8Length(
                    replacement.newText(), remaining, "edit text");
            remaining -= newBytes;
        }
    }

    private static String decodeUtf8(byte[] bytes, int offset) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("file is not valid UTF-8", e);
        }
    }

    private static NormalizedText normalizeWithMapping(String original) {
        var normalized = new StringBuilder(original.length());
        var offsets = new int[original.length() + 1];
        int logical = 0;
        offsets[0] = 0;
        for (int raw = 0; raw < original.length();) {
            char current = original.charAt(raw);
            if (current == '\r') {
                int nextRaw = raw + 1 < original.length() && original.charAt(raw + 1) == '\n'
                        ? raw + 2
                        : raw + 1;
                normalized.append('\n');
                offsets[++logical] = nextRaw;
                raw = nextRaw;
            } else {
                normalized.append(current);
                offsets[++logical] = ++raw;
            }
        }
        return new NormalizedText(normalized.toString(), Arrays.copyOf(offsets, logical + 1));
    }

    private static String normalizeNewlines(String text) {
        var normalized = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\r') {
                if (index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                normalized.append('\n');
            } else {
                normalized.append(current);
            }
        }
        return normalized.toString();
    }

    private static String restoreNewlines(String text, String newline) {
        if ("\n".equals(newline) || text.indexOf('\n') < 0) {
            return text;
        }
        return text.replace("\n", newline);
    }

    private static String firstNewline(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\r') {
                return index + 1 < text.length() && text.charAt(index + 1) == '\n'
                        ? "\r\n"
                        : "\r";
            }
            if (current == '\n') {
                return "\n";
            }
        }
        return null;
    }

    private static int lineNumberAt(String text, int offset) {
        int line = 1;
        for (int index = 0; index < offset; index++) {
            char current = text.charAt(index);
            if (current == '\r') {
                if (index + 1 < offset && text.charAt(index + 1) == '\n') {
                    index++;
                }
                line++;
            } else if (current == '\n') {
                line++;
            }
        }
        return line;
    }

    private static boolean startsWithBom(byte[] bytes) {
        return bytes.length >= UTF8_BOM.length
                && bytes[0] == UTF8_BOM[0]
                && bytes[1] == UTF8_BOM[1]
                && bytes[2] == UTF8_BOM[2];
    }

    private static byte[] withBom(byte[] content) {
        var result = Arrays.copyOf(UTF8_BOM, UTF8_BOM.length + content.length);
        System.arraycopy(content, 0, result, UTF8_BOM.length, content.length);
        return result;
    }

    private record NormalizedText(String text, int[] rawOffsets) {
    }

    private record PlannedReplacement(int start, int end, String replacement) {
    }
}
