package site.pplee.jcode.codingagent.tool;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Plans bounded exact replacements without performing filesystem I/O. */
final class EditPlanner {
    private static final byte[] UTF8_BOM = {
            (byte) 0xef, (byte) 0xbb, (byte) 0xbf
    };

    /** Match every replacement against the same original byte sequence. */
    EditPlan plan(byte[] originalBytes, List<EditReplacement> replacements) {
        Objects.requireNonNull(originalBytes, "originalBytes must not be null");
        Objects.requireNonNull(replacements, "replacements must not be null");
        if (originalBytes.length > FileMutationWriter.MAX_FILE_BYTES) {
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
        String fileNewline = firstNewline(original);
        var ranges = new ArrayList<PlannedReplacement>(replacements.size());

        for (int index = 0; index < replacements.size(); index++) {
            EditReplacement replacement = Objects.requireNonNull(
                    replacements.get(index), "edits must not contain null");
            String oldText = normalizeNewlines(replacement.oldText());
            if (oldText.isEmpty()) {
                throw new IllegalArgumentException("oldText must not be empty");
            }
            int match = normalized.text().indexOf(oldText);
            if (match < 0) {
                throw new IllegalArgumentException("edit " + (index + 1) + " oldText was not found");
            }
            if (normalized.text().indexOf(oldText, match + 1) >= 0) {
                throw new IllegalArgumentException(
                        "edit " + (index + 1) + " oldText occurs more than once");
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
                result.toString(), FileMutationWriter.MAX_FILE_BYTES - bomBytes, "edit result");
        byte[] finalBytes = hasBom ? withBom(encoded) : encoded;
        int firstLine = lineNumberAt(original, ranges.getFirst().start());
        return new EditPlan(
                finalBytes,
                replacements.size(),
                firstLine,
                !Arrays.equals(originalBytes, finalBytes));
    }

    private static void validateReplacementBudget(List<EditReplacement> replacements) {
        int remaining = FileMutationWriter.MAX_FILE_BYTES;
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
