package site.pplee.jcode.codingagent.tool;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.FileSystemException;
import java.util.Objects;

/** Shared validation and bounded UTF-8 helpers for local file tools. */
final class FileToolSupport {
    static final int MAX_PATH_CHARACTERS = 4096;
    static final int MAX_DIAGNOSTIC_CHARACTERS = 1024;
    private FileToolSupport() {
    }

    static String validatePath(String path) {
        Objects.requireNonNull(path, "path must not be null");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        if (path.length() > MAX_PATH_CHARACTERS) {
            throw new IllegalArgumentException("path exceeds the length limit");
        }
        if (path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("path must not contain NUL");
        }
        validateWellFormedUtf16(path, "path");
        return path;
    }

    static byte[] encodeUtf8(String value, int maximumBytes, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        int length = utf8Length(value, maximumBytes, label);
        var encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(value));
            var result = new byte[length];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(label + " must be well-formed UTF-16", e);
        }
    }

    static int utf8Length(String value, int maximumBytes, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            int width;
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(label + " must be well-formed UTF-16");
                }
                index++;
                width = 4;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(label + " must be well-formed UTF-16");
            } else if (current <= 0x7f) {
                width = 1;
            } else if (current <= 0x7ff) {
                width = 2;
            } else {
                width = 3;
            }
            if (bytes > maximumBytes - width) {
                throw new IllegalArgumentException(label + " exceeds the "
                        + maximumBytes + " byte limit");
            }
            bytes += width;
        }
        return bytes;
    }

    static String safeMessage(Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        String diagnostic;
        if (failure instanceof InvalidPathException) {
            diagnostic = "invalid path";
        } else if (failure instanceof SecurityException) {
            diagnostic = "filesystem operation was denied";
        } else if (failure instanceof FileSystemException) {
            diagnostic = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        } else {
            String message = failure.getMessage();
            diagnostic = message == null || message.isBlank()
                    ? failure.getClass().getSimpleName()
                    : message;
        }
        if (diagnostic.length() <= MAX_DIAGNOSTIC_CHARACTERS) {
            return diagnostic;
        }
        return diagnostic.substring(0, MAX_DIAGNOSTIC_CHARACTERS - 3) + "...";
    }

    private static void validateWellFormedUtf16(String value, String label) {
        utf8Length(value, Integer.MAX_VALUE, label);
    }
}
