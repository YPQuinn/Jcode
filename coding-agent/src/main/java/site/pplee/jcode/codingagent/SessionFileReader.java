package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.session.SessionEntries;
import site.pplee.jcode.codingagent.session.SessionEntry;
import site.pplee.jcode.codingagent.session.SessionFormatException;
import site.pplee.jcode.codingagent.session.SessionHeader;

import com.fasterxml.jackson.core.io.JsonEOFException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Streaming JSONL reader with narrowly defined final-line recovery. */
final class SessionFileReader {
    private static final int READ_BUFFER_SIZE = 8192;

    private final Path path;
    private final SessionCodec codec;
    private final List<SessionEntry> entries = new ArrayList<>();
    private final Map<String, SessionEntry> byId = new LinkedHashMap<>();
    private SessionHeader header;

    SessionFileReader(Path path, SessionCodec codec) {
        this.path = path;
        this.codec = codec;
    }

    ReadResult read(FileChannel channel) throws IOException {
        channel.position(0);
        var buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
        var line = new ByteArrayOutputStream();
        long offset = 0;
        long lineStart = 0;
        long lineNumber = 1;

        while (channel.read(buffer) != -1) {
            buffer.flip();
            while (buffer.hasRemaining()) {
                byte value = buffer.get();
                offset++;
                if (value == '\n') {
                    processCompleteLine(line.toByteArray(), lineNumber, lineStart);
                    line.reset();
                    lineStart = offset;
                    lineNumber++;
                } else {
                    line.write(value);
                }
            }
            buffer.clear();
        }

        TailRecovery recovery = null;
        boolean needsSeparator = line.size() > 0;
        if (line.size() > 0) {
            var tail = line.toByteArray();
            try {
                processFinalLine(tail, lineNumber, lineStart);
            } catch (RecoverableTailException e) {
                if (header == null) {
                    throw invalid(lineNumber, lineStart,
                            "the header is incomplete", e.getCause());
                }
                recovery = new TailRecovery(lineNumber, lineStart, tail.length, e.reason());
                needsSeparator = false;
            }
        }
        if (header == null) {
            throw invalid(1, 0, "a versioned header is required", null);
        }
        long appendOffset = recovery == null ? offset : recovery.byteOffset();
        return new ReadResult(header, SessionEntries.copyAll(entries), appendOffset,
                needsSeparator, recovery);
    }

    private void processCompleteLine(byte[] bytes, long lineNumber, long lineStart)
            throws SessionFormatException {
        try {
            var decoded = decodeStrict(bytes);
            if (!decoded.isBlank()) {
                decodeRecord(bytes);
            }
        } catch (Exception e) {
            throw invalid(lineNumber, lineStart, reason(e), e);
        }
    }

    private void processFinalLine(byte[] bytes, long lineNumber, long lineStart)
            throws SessionFormatException, RecoverableTailException {
        final String decoded;
        try {
            decoded = decodeStrict(bytes);
        } catch (Exception e) {
            if (hasIncompleteUtf8Suffix(bytes)) {
                throw new RecoverableTailException(TailReason.INCOMPLETE_UTF8, e);
            }
            throw invalid(lineNumber, lineStart, "invalid UTF-8", e);
        }
        if (decoded.isBlank()) {
            return;
        }
        try {
            decodeRecord(bytes);
        } catch (Exception e) {
            if (containsJsonEof(e)) {
                throw new RecoverableTailException(TailReason.TRUNCATED_JSON, e);
            }
            throw invalid(lineNumber, lineStart, reason(e), e);
        }
    }

    private void decodeRecord(byte[] bytes) throws IOException {
        if (header == null) {
            header = codec.decodeHeader(bytes);
            return;
        }
        var entry = codec.decodeEntry(bytes);
        SessionEntries.validateNext(entry, byId);
        entries.add(entry);
        byId.put(entry.id(), entry);
    }

    private SessionFormatException invalid(
            long lineNumber,
            long byteOffset,
            String reason,
            Throwable cause
    ) {
        return new SessionFormatException(path, lineNumber, byteOffset, reason, cause);
    }

    private static String decodeStrict(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static boolean containsJsonEof(Throwable throwable) {
        for (var current = throwable; current != null; current = current.getCause()) {
            if (current instanceof JsonEOFException) {
                return true;
            }
        }
        return false;
    }

    private static String reason(Exception exception) {
        if (exception instanceof java.nio.charset.CharacterCodingException) {
            return "invalid UTF-8";
        }
        var message = exception.getMessage();
        return message == null || message.isBlank() ? "invalid record" : message;
    }

    private static boolean hasIncompleteUtf8Suffix(byte[] bytes) {
        int leadIndex = bytes.length - 1;
        int continuationCount = 0;
        while (leadIndex >= 0 && continuationCount < 3 && isContinuation(bytes[leadIndex])) {
            continuationCount++;
            leadIndex--;
        }
        if (leadIndex < 0) {
            return false;
        }
        int lead = Byte.toUnsignedInt(bytes[leadIndex]);
        int expectedLength = expectedUtf8Length(lead);
        if (expectedLength < 0 || 1 + continuationCount >= expectedLength) {
            return false;
        }
        if (continuationCount > 0
                && !isValidFirstContinuation(lead, Byte.toUnsignedInt(bytes[leadIndex + 1]))) {
            return false;
        }
        try {
            decodeStrict(Arrays.copyOf(bytes, leadIndex));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    private static int expectedUtf8Length(int lead) {
        if (lead >= 0xC2 && lead <= 0xDF) {
            return 2;
        }
        if (lead >= 0xE0 && lead <= 0xEF) {
            return 3;
        }
        if (lead >= 0xF0 && lead <= 0xF4) {
            return 4;
        }
        return -1;
    }

    private static boolean isValidFirstContinuation(int lead, int continuation) {
        if (lead == 0xE0) {
            return continuation >= 0xA0 && continuation <= 0xBF;
        }
        if (lead == 0xED) {
            return continuation >= 0x80 && continuation <= 0x9F;
        }
        if (lead == 0xF0) {
            return continuation >= 0x90 && continuation <= 0xBF;
        }
        if (lead == 0xF4) {
            return continuation >= 0x80 && continuation <= 0x8F;
        }
        return continuation >= 0x80 && continuation <= 0xBF;
    }

    private static boolean isContinuation(byte value) {
        int unsigned = Byte.toUnsignedInt(value);
        return unsigned >= 0x80 && unsigned <= 0xBF;
    }

    enum TailReason {
        TRUNCATED_JSON,
        INCOMPLETE_UTF8
    }

    record TailRecovery(long lineNumber, long byteOffset, long discardedBytes, TailReason reason) {
    }

    record ReadResult(
            SessionHeader header,
            List<SessionEntry> entries,
            long appendOffset,
            boolean needsSeparator,
            TailRecovery recovery
    ) {
    }

    private static final class RecoverableTailException extends Exception {
        private final TailReason reason;

        private RecoverableTailException(TailReason reason, Throwable cause) {
            super(cause);
            this.reason = reason;
        }

        private TailReason reason() {
            return reason;
        }
    }
}
