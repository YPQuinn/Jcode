package site.pplee.jcode.codingagent.tool;

import java.util.Arrays;
import java.util.Objects;

/** Incrementally splits bounded byte records without buffering oversized input. */
final class BoundedByteRecordReader {
    @FunctionalInterface
    interface RecordConsumer {
        void accept(byte[] record);
    }

    private final byte delimiter;
    private final byte[] buffer;
    private final int maximumRecords;
    private final RecordConsumer consumer;

    private int length;
    private int recordCount;
    private int oversizedRecords;
    private boolean discarding;
    private boolean scanLimitReached;
    private boolean finished;

    BoundedByteRecordReader(
            byte delimiter,
            int maximumRecordBytes,
            int maximumRecords,
            RecordConsumer consumer
    ) {
        if (maximumRecordBytes < 1) {
            throw new IllegalArgumentException("maximumRecordBytes must be positive");
        }
        if (maximumRecords < 1) {
            throw new IllegalArgumentException("maximumRecords must be positive");
        }
        this.delimiter = delimiter;
        this.buffer = new byte[maximumRecordBytes];
        this.maximumRecords = maximumRecords;
        this.consumer = Objects.requireNonNull(consumer, "consumer must not be null");
    }

    void append(byte[] bytes, int offset, int count) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        if (finished) {
            throw new IllegalStateException("record reader is finished");
        }
        if (offset < 0 || count < 0 || offset > bytes.length - count) {
            throw new IndexOutOfBoundsException("invalid byte range");
        }
        if (scanLimitReached) {
            return;
        }
        int end = offset + count;
        for (int index = offset; index < end; index++) {
            byte current = bytes[index];
            if (current == delimiter) {
                completeRecord();
                if (scanLimitReached) {
                    return;
                }
            } else if (!discarding) {
                if (length < buffer.length) {
                    buffer[length++] = current;
                } else {
                    discarding = true;
                    length = 0;
                }
            }
        }
    }

    void finish(boolean allowUnterminatedFinalRecord) {
        if (finished) {
            return;
        }
        finished = true;
        if (scanLimitReached || (!discarding && length == 0)) {
            return;
        }
        if (!allowUnterminatedFinalRecord) {
            throw new IllegalArgumentException("unterminated structured search record");
        }
        completeRecord();
    }

    int recordCount() {
        return recordCount;
    }

    int oversizedRecords() {
        return oversizedRecords;
    }

    boolean scanLimitReached() {
        return scanLimitReached;
    }

    private void completeRecord() {
        if (recordCount >= maximumRecords) {
            scanLimitReached = true;
            length = 0;
            discarding = false;
            return;
        }
        recordCount++;
        if (discarding) {
            oversizedRecords++;
        } else {
            consumer.accept(Arrays.copyOf(buffer, length));
        }
        length = 0;
        discarding = false;
    }
}
