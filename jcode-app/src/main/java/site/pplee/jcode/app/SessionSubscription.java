package site.pplee.jcode.app;

import site.pplee.jcode.protocol.ErrorCode;
import site.pplee.jcode.protocol.SessionEvent;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Independent bounded delivery queue; closing it never changes session execution. */
public final class SessionSubscription implements AutoCloseable {
    static final int CAPACITY = 128;
    private static final Object RESYNC = new Object();
    private static final Object CLOSED = new Object();

    private final SessionFeed feed;
    private final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(CAPACITY);
    private volatile boolean expired;
    private volatile boolean clientClosed;
    private volatile boolean serverClosed;

    SessionSubscription(SessionFeed feed) {
        this.feed = feed;
    }

    /** Wait on the caller's thread; an exhausted server-closed stream reports SESSION_CLOSED. */
    public Optional<SessionEvent> next(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        if (expired) {
            throw new ApiException(ErrorCode.SUBSCRIBER_SLOW, "subscription fell behind; resync required");
        }
        if (clientClosed) {
            return Optional.empty();
        }
        if (serverClosed && queue.isEmpty()) {
            throw new ApiException(ErrorCode.SESSION_CLOSED, "session stream is closed");
        }
        Object item = queue.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        if (item == RESYNC || expired) {
            throw new ApiException(ErrorCode.SUBSCRIBER_SLOW, "subscription fell behind; resync required");
        }
        if (clientClosed) {
            return Optional.empty();
        }
        if (item == CLOSED || (item == null && serverClosed)) {
            throw new ApiException(ErrorCode.SESSION_CLOSED, "session stream is closed");
        }
        if (item == null) {
            return Optional.empty();
        }
        return Optional.of((SessionEvent) item);
    }

    boolean offer(SessionEvent event) {
        return queue.offer(event);
    }

    void expire() {
        expired = true;
        queue.clear();
        queue.offer(RESYNC);
    }

    @Override
    public void close() {
        feed.unsubscribe(this);
        clientClosed = true;
        queue.clear();
        queue.offer(CLOSED);
    }

    /** Preserve already accepted events and wake a reader waiting on an empty queue. */
    void finishFromServer() {
        serverClosed = true;
        queue.offer(CLOSED);
    }
}
