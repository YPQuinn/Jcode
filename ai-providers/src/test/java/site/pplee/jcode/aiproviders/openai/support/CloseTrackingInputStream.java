package site.pplee.jcode.aiproviders.openai.support;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controllable body for adapter tests. Records {@link #close()} and can
 * block the first read until the stream is closed so cancellation races
 * are deterministic.
 */
public final class CloseTrackingInputStream extends InputStream {
    private final byte[] data;
    private final boolean blockUntilClosed;
    private int pos;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger closeCount = new AtomicInteger();
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private final CountDownLatch firstRead = new CountDownLatch(1);

    public CloseTrackingInputStream(byte[] data) {
        this(data, false);
    }

    public CloseTrackingInputStream(byte[] data, boolean blockUntilClosed) {
        this.data = data == null ? new byte[0] : data;
        this.blockUntilClosed = blockUntilClosed;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public int closeCount() {
        return closeCount.get();
    }

    public boolean awaitFirstRead(long timeout, TimeUnit unit) throws InterruptedException {
        return firstRead.await(timeout, unit);
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : (one[0] & 0xff);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        firstRead.countDown();
        if (blockUntilClosed) {
            awaitClose();
            throw new IOException("closed");
        }
        if (closed.get()) {
            throw new IOException("closed");
        }
        if (pos >= data.length) {
            return -1;
        }
        int n = Math.min(length, data.length - pos);
        System.arraycopy(data, pos, buffer, offset, n);
        pos += n;
        return n;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeCount.incrementAndGet();
            closedLatch.countDown();
        } else {
            closeCount.incrementAndGet();
        }
    }

    private void awaitClose() throws IOException {
        try {
            closedLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
