package site.pplee.jcode.codingagent.tool;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Incrementally decodes process bytes while retaining only a bounded text tail. */
final class ProcessOutputBuffer {
    private static final String REPLACEMENT = "\ufffd";

    private final int maximumLines;
    private final int maximumBytes;
    private final int maximumRetainedBytes;
    private final java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    private final StringBuilder tail = new StringBuilder();

    private byte[] undecoded = new byte[0];
    private int tailUtf8Bytes;
    private boolean tailStartsAtLineBoundary = true;
    private long completedLines;
    private boolean hasOpenLine;
    private long totalUtf8Bytes;
    private boolean invalidUtf8;
    private boolean finished;

    ProcessOutputBuffer(int maximumLines, int maximumBytes) {
        if (maximumLines < 1) {
            throw new IllegalArgumentException("maximumLines must be positive");
        }
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.maximumLines = maximumLines;
        this.maximumBytes = maximumBytes;
        this.maximumRetainedBytes = Math.multiplyExact(maximumBytes, 2);
    }

    synchronized void append(byte[] bytes, int offset, int length) {
        if (finished) {
            throw new IllegalStateException("output buffer is finished");
        }
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException("invalid output byte range");
        }
        if (length == 0) {
            return;
        }
        var inputBytes = Arrays.copyOf(undecoded, undecoded.length + length);
        System.arraycopy(bytes, offset, inputBytes, undecoded.length, length);
        decode(ByteBuffer.wrap(inputBytes), false);
    }

    synchronized void finish() {
        if (finished) {
            return;
        }
        finished = true;
        decode(ByteBuffer.wrap(undecoded), true);
        undecoded = new byte[0];
        var characters = CharBuffer.allocate(8);
        while (true) {
            CoderResult result = decoder.flush(characters);
            appendCharacters(characters);
            if (result.isUnderflow()) {
                break;
            }
        }
    }

    synchronized ProcessOutputSnapshot snapshot() {
        String retained = tail.toString();
        Selection lineSelection = selectLines(retained, tailStartsAtLineBoundary);
        boolean byteSelectionRequired = lineSelection.content()
                .getBytes(StandardCharsets.UTF_8).length > maximumBytes;
        Selection byteSelection = selectBytes(
                lineSelection.content(), lineSelection.startsAtLineBoundary());
        String content = byteSelection.content();
        int outputBytes = content.getBytes(StandardCharsets.UTF_8).length;
        int outputLines = countLines(content);
        long totalLines = totalLines();
        boolean truncatedByLines = totalLines > maximumLines;
        boolean truncatedByBytes = totalUtf8Bytes > maximumBytes;
        boolean truncated = truncatedByLines || truncatedByBytes;
        ProcessOutputSnapshot.TruncatedBy truncatedBy = !truncated
                ? null
                : byteSelectionRequired || !truncatedByLines
                ? ProcessOutputSnapshot.TruncatedBy.BYTES
                : ProcessOutputSnapshot.TruncatedBy.LINES;
        return new ProcessOutputSnapshot(
                content,
                totalLines,
                totalUtf8Bytes,
                outputLines,
                outputBytes,
                truncated,
                truncatedBy,
                !byteSelection.startsAtLineBoundary() && !content.isEmpty(),
                invalidUtf8);
    }

    synchronized int retainedUtf8Bytes() {
        return tailUtf8Bytes;
    }

    private void decode(ByteBuffer input, boolean endOfInput) {
        while (true) {
            int capacity = Math.max(8,
                    (int) Math.ceil(Math.max(1, input.remaining()) * decoder.maxCharsPerByte()) + 1);
            var characters = CharBuffer.allocate(capacity);
            CoderResult result = decoder.decode(input, characters, endOfInput);
            appendCharacters(characters);
            if (result.isError()) {
                invalidUtf8 = true;
                input.position(Math.min(input.limit(), input.position() + result.length()));
                appendDecoded(REPLACEMENT);
                continue;
            }
            if (result.isOverflow()) {
                continue;
            }
            undecoded = new byte[input.remaining()];
            input.get(undecoded);
            return;
        }
    }

    private void appendCharacters(CharBuffer characters) {
        characters.flip();
        if (characters.hasRemaining()) {
            appendDecoded(characters.toString());
        }
    }

    private void appendDecoded(String text) {
        if (text.isEmpty()) {
            return;
        }
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        totalUtf8Bytes = saturatedAdd(totalUtf8Bytes, bytes);
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\n') {
                completedLines = saturatedAdd(completedLines, 1);
                hasOpenLine = false;
            } else {
                hasOpenLine = true;
            }
        }
        tail.append(text);
        tailUtf8Bytes += bytes;
        if (tailUtf8Bytes > maximumRetainedBytes) {
            trimRetainedTail();
        }
    }

    private void trimRetainedTail() {
        String current = tail.toString();
        byte[] encoded = current.getBytes(StandardCharsets.UTF_8);
        int byteStart = Math.max(0, encoded.length - maximumRetainedBytes);
        while (byteStart < encoded.length && (encoded[byteStart] & 0xc0) == 0x80) {
            byteStart++;
        }
        String suffix = new String(encoded, byteStart, encoded.length - byteStart, StandardCharsets.UTF_8);
        int charStart = current.length() - suffix.length();
        if (charStart > 0) {
            tailStartsAtLineBoundary = current.charAt(charStart - 1) == '\n';
        }
        tail.setLength(0);
        tail.append(suffix);
        tailUtf8Bytes = suffix.getBytes(StandardCharsets.UTF_8).length;
    }

    private Selection selectLines(String text, boolean startsAtBoundary) {
        if (totalLines() <= maximumLines || text.isEmpty()) {
            return new Selection(text, startsAtBoundary);
        }
        int boundariesNeeded = maximumLines + (text.endsWith("\n") ? 1 : 0);
        int boundaries = 0;
        for (int index = text.length() - 1; index >= 0; index--) {
            if (text.charAt(index) == '\n' && ++boundaries == boundariesNeeded) {
                return new Selection(text.substring(index + 1), true);
            }
        }
        return new Selection(text, startsAtBoundary);
    }

    private Selection selectBytes(String text, boolean startsAtBoundary) {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= maximumBytes) {
            return new Selection(text, startsAtBoundary);
        }
        int byteStart = encoded.length - maximumBytes;
        while (byteStart < encoded.length && (encoded[byteStart] & 0xc0) == 0x80) {
            byteStart++;
        }
        String suffix = new String(encoded, byteStart, encoded.length - byteStart, StandardCharsets.UTF_8);
        int charStart = text.length() - suffix.length();
        boolean boundary = charStart == 0
                ? startsAtBoundary
                : text.charAt(charStart - 1) == '\n';
        return new Selection(suffix, boundary);
    }

    private long totalLines() {
        return saturatedAdd(completedLines, hasOpenLine ? 1 : 0);
    }

    private static int countLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int lines = text.endsWith("\n") ? 0 : 1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static long saturatedAdd(long current, long increment) {
        return current > Long.MAX_VALUE - increment ? Long.MAX_VALUE : current + increment;
    }

    private record Selection(String content, boolean startsAtLineBoundary) {
    }
}
