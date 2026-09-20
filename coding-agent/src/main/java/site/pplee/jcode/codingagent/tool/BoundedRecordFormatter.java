package site.pplee.jcode.codingagent.tool;

import java.util.List;
import java.util.Objects;

/** Builds bounded single-line record output without returning partial records. */
final class BoundedRecordFormatter {
    enum AppendResult {
        ADDED,
        RECORD_TOO_LARGE,
        OUTPUT_FULL
    }

    private final int maximumRecords;
    private final int maximumUtf8Bytes;
    private final StringBuilder content = new StringBuilder();
    private int recordCount;
    private int utf8Bytes;

    BoundedRecordFormatter(int maximumRecords, int maximumUtf8Bytes) {
        if (maximumRecords < 1) {
            throw new IllegalArgumentException("maximumRecords must be positive");
        }
        if (maximumUtf8Bytes < 1) {
            throw new IllegalArgumentException("maximumUtf8Bytes must be positive");
        }
        this.maximumRecords = maximumRecords;
        this.maximumUtf8Bytes = maximumUtf8Bytes;
    }

    AppendResult append(String record) {
        Objects.requireNonNull(record, "record must not be null");
        if (record.isEmpty()) {
            throw new IllegalArgumentException("record must not be empty");
        }
        if (record.indexOf('\n') >= 0 || record.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("record must occupy one physical line");
        }
        int recordBytes = boundedUtf8Length(record, maximumUtf8Bytes);
        if (recordBytes < 0) {
            return AppendResult.RECORD_TOO_LARGE;
        }
        int separatorBytes = recordCount == 0 ? 0 : 1;
        if (recordCount >= maximumRecords
                || utf8Bytes > maximumUtf8Bytes - separatorBytes - recordBytes) {
            return AppendResult.OUTPUT_FULL;
        }
        if (separatorBytes != 0) {
            content.append('\n');
        }
        content.append(record);
        recordCount++;
        utf8Bytes += separatorBytes + recordBytes;
        return AppendResult.ADDED;
    }

    String content() {
        return content.toString();
    }

    int recordCount() {
        return recordCount;
    }

    int utf8Bytes() {
        return utf8Bytes;
    }

    /** Append bounded one-line notices outside the primary record budget. */
    static String withNotices(
            String body,
            List<String> notices,
            int maximumNoticeLines,
            int maximumNoticeUtf8Bytes
    ) {
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(notices, "notices must not be null");
        var formatted = new BoundedRecordFormatter(maximumNoticeLines, maximumNoticeUtf8Bytes);
        for (String notice : notices) {
            String line = '[' + Objects.requireNonNull(notice, "notice must not be null") + ']';
            formatted.append(line);
        }
        if (formatted.recordCount() == 0) {
            return body;
        }
        return body.isEmpty() ? formatted.content() : body + "\n\n" + formatted.content();
    }

    private static int boundedUtf8Length(String value, int maximumBytes) {
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            int width;
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("record must be well-formed UTF-16");
                }
                index++;
                width = 4;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("record must be well-formed UTF-16");
            } else if (current <= 0x7f) {
                width = 1;
            } else if (current <= 0x7ff) {
                width = 2;
            } else {
                width = 3;
            }
            if (bytes > maximumBytes - width) {
                return -1;
            }
            bytes += width;
        }
        return bytes;
    }
}
