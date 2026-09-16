package site.pplee.jcode.agentcore.concurrent;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.concurrent.CancellationRegistration;
import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationSourceTest {
    @Test
    void isNotCancelledInitially() {
        var source = new CancellationSource();
        assertFalse(source.isCancelled());
        assertFalse(source.signal().isCancelled());
    }

    @Test
    void cancelFlipsState() {
        var source = new CancellationSource();
        source.cancel();
        assertTrue(source.isCancelled());
        assertTrue(source.signal().isCancelled());
    }

    @Test
    void cancelIsIdempotent() {
        var source = new CancellationSource();
        source.cancel();
        source.cancel();
        source.cancel();
        assertTrue(source.isCancelled());
    }

    @Test
    void throwIfCancelledNoopWhenNotCancelled() {
        var source = new CancellationSource();
        source.throwIfCancelled();
        source.signal().throwIfCancelled();
    }

    @Test
    void throwIfCancelledThrowsWhenCancelled() {
        var source = new CancellationSource();
        source.cancel();

        assertThrows(CancellationException.class, source::throwIfCancelled);
        assertThrows(CancellationException.class, () -> source.signal().throwIfCancelled());
    }

    @Test
    void signalIsStableReference() {
        var source = new CancellationSource();
        var first = source.signal();
        var second = source.signal();
        assertSame(first, second);
    }

    @Test
    void signalReflectsCancellation() {
        var source = new CancellationSource();
        var signal = source.signal();
        assertFalse(signal.isCancelled());
        source.cancel();
        assertTrue(signal.isCancelled());
    }

    @Test
    void signalIsNotACancellationSource() {
        CancellationSignal signal = new CancellationSource().signal();
        // The signal is a private SignalView. Static types are inconvertible
        // (so `signal instanceof CancellationSource` won't even compile), and
        // at runtime the signal object is not a CancellationSource instance.
        assertFalse(CancellationSource.class.isInstance(signal));
        assertFalse(CancellationSource.class.isAssignableFrom(signal.getClass()));
    }

    @Test
    void listenerRunsOnceOnCancel() {
        var source = new CancellationSource();
        var runs = new AtomicInteger();
        source.signal().onCancellation(runs::incrementAndGet);
        source.cancel();
        source.cancel();
        assertEquals(1, runs.get());
    }

    @Test
    void lateRegistrationRunsListenerBeforeReturn() {
        var source = new CancellationSource();
        source.cancel();
        var runs = new AtomicInteger();
        source.signal().onCancellation(runs::incrementAndGet);
        assertEquals(1, runs.get());
    }

    @Test
    void closingRegistrationBeforeCancelPreventsListener() {
        var source = new CancellationSource();
        var runs = new AtomicInteger();
        CancellationRegistration registration = source.signal().onCancellation(runs::incrementAndGet);
        registration.close();
        registration.close();
        source.cancel();
        assertEquals(0, runs.get());
    }

    @Test
    void listenerFailureDoesNotFailCancelOrOtherListeners() {
        var source = new CancellationSource();
        var later = new AtomicBoolean();
        source.signal().onCancellation(() -> {
            throw new RuntimeException("listener boom");
        });
        source.signal().onCancellation(() -> later.set(true));
        assertDoesNotThrow(source::cancel);
        assertTrue(later.get());
        assertTrue(source.isCancelled());
    }

    @Test
    void concurrentCancelAndRegisterExecutesEachListenerOnce() throws Exception {
        var source = new CancellationSource();
        int listeners = 8;
        var executions = new AtomicInteger();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(listeners + 1);
        for (int i = 0; i < listeners; i++) {
            Thread.startVirtualThread(() -> {
                try {
                    start.await();
                    source.signal().onCancellation(executions::incrementAndGet);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        Thread.startVirtualThread(() -> {
            try {
                start.await();
                source.cancel();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(listeners, executions.get());
    }
}
