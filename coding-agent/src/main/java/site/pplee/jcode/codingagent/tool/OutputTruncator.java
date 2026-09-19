package site.pplee.jcode.codingagent.tool;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.Objects;
import java.util.OptionalInt;

/** Bounded UTF-8 head truncation that never returns a partial physical line. */
public final class OutputTruncator {
    private static final int CANCELLATION_CHECK_INTERVAL = 4096;

    private OutputTruncator() {
    }

    /**
     * Read a page of normalized physical lines.
     *
     * @param source source already configured with the desired character decoder
     * @param offset first physical line to return, one-based
     * @param lineLimit maximum complete lines to return
     * @param byteLimit maximum UTF-8 bytes after line-ending normalization
     * @param cancellation cancellation signal observed while scanning
     */
    public static TruncatedOutput truncate(
            Reader source,
            int offset,
            int lineLimit,
            int byteLimit,
            CancellationSignal cancellation
    ) throws IOException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        if (offset < 1 || lineLimit < 1 || byteLimit < 1) {
            throw new IllegalArgumentException("offset, lineLimit, and byteLimit must be positive");
        }
        var reader = new PhysicalLineReader(source, cancellation);
        for (int lineNumber = 1; lineNumber < offset; lineNumber++) {
            if (!reader.skipLine()) {
                return empty();
            }
        }

        var content = new StringBuilder();
        int outputLines = 0;
        long outputBytes = 0;
        int currentLine = offset;
        PhysicalLine line = reader.next(byteLimit);
        while (line != null) {
            if (line.overlong()) {
                if (outputLines == 0) {
                    throw new LineTooLongException(currentLine, byteLimit);
                }
                return truncated(content, outputLines, outputBytes, currentLine);
            }
            content.append(line.content());
            if (line.terminated()) {
                content.append('\n');
            }
            outputLines++;
            outputBytes += line.utf8Bytes();

            if (outputLines == lineLimit || outputBytes == byteLimit) {
                if (!reader.hasNext()) {
                    return complete(content, outputLines, outputBytes);
                }
                return truncated(content, outputLines, outputBytes, addOffset(offset, outputLines));
            }

            currentLine = addOffset(offset, outputLines);
            line = reader.next((int) (byteLimit - outputBytes));
        }
        return complete(content, outputLines, outputBytes);
    }

    private static TruncatedOutput empty() {
        return new TruncatedOutput("", false, 0, 0, OptionalInt.empty());
    }

    private static TruncatedOutput complete(StringBuilder content, int lines, long bytes) {
        return new TruncatedOutput(content.toString(), false, lines, bytes, OptionalInt.empty());
    }

    private static TruncatedOutput truncated(StringBuilder content, int lines, long bytes, int nextOffset) {
        return new TruncatedOutput(content.toString(), true, lines, bytes, OptionalInt.of(nextOffset));
    }

    private static int addOffset(int offset, int lines) {
        try {
            return Math.addExact(offset, lines);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("continuation offset exceeds the supported line range", e);
        }
    }

    private record PhysicalLine(String content, boolean terminated, long utf8Bytes, boolean overlong) {
    }

    private static final class PhysicalLineReader {
        private final PushbackReader reader;
        private final CancellationSignal cancellation;
        private int charsSinceCancellationCheck;

        private PhysicalLineReader(Reader source, CancellationSignal cancellation) {
            this.reader = new PushbackReader(source, 1);
            this.cancellation = cancellation;
        }

        private boolean hasNext() throws IOException {
            cancellation.throwIfCancelled();
            int value = read();
            if (value == -1) {
                return false;
            }
            reader.unread(value);
            return true;
        }

        /** Skip a whole physical line without retaining its content. */
        private boolean skipLine() throws IOException {
            return scanLine(0, true) != null;
        }

        /** Read until the line ends or its normalized UTF-8 size exceeds the remaining budget. */
        private PhysicalLine next(int byteBudget) throws IOException {
            return scanLine(byteBudget, false);
        }

        /** Share decoding and cancellation checks; skipped lines ignore the collection budget. */
        private PhysicalLine scanLine(int byteBudget, boolean skip) throws IOException {
            cancellation.throwIfCancelled();
            var content = new StringBuilder();
            long bytes = 0;
            boolean sawCharacter = false;

            while (true) {
                int value = read();
                if (value == -1) {
                    if (!sawCharacter) {
                        return null;
                    }
                    return new PhysicalLine(content.toString(), false, bytes, false);
                }
                sawCharacter = true;
                char character = (char) value;
                if (character == '\n') {
                    bytes++;
                    return new PhysicalLine(content.toString(), true, bytes, !skip && bytes > byteBudget);
                }
                if (character == '\r') {
                    int following = read();
                    if (following != '\n' && following != -1) {
                        reader.unread(following);
                    }
                    bytes++;
                    return new PhysicalLine(content.toString(), true, bytes, !skip && bytes > byteBudget);
                }
                if (character == 0) {
                    throw new IOException("file appears to contain binary data");
                }

                int codePoint;
                if (Character.isHighSurrogate(character)) {
                    int following = read();
                    if (following == -1 || !Character.isLowSurrogate((char) following)) {
                        throw new IOException("text contains an unpaired surrogate");
                    }
                    codePoint = Character.toCodePoint(character, (char) following);
                } else if (Character.isLowSurrogate(character)) {
                    throw new IOException("text contains an unpaired surrogate");
                } else {
                    codePoint = character;
                }
                if (!skip) {
                    bytes += utf8Length(codePoint);
                    if (bytes > byteBudget) {
                        return new PhysicalLine("", false, bytes, true);
                    }
                    content.appendCodePoint(codePoint);
                }
            }
        }

        private int read() throws IOException {
            if (++charsSinceCancellationCheck >= CANCELLATION_CHECK_INTERVAL) {
                cancellation.throwIfCancelled();
                charsSinceCancellationCheck = 0;
            }
            return reader.read();
        }

        private static int utf8Length(int codePoint) {
            if (codePoint <= 0x7f) {
                return 1;
            }
            if (codePoint <= 0x7ff) {
                return 2;
            }
            return codePoint <= 0xffff ? 3 : 4;
        }
    }
}
