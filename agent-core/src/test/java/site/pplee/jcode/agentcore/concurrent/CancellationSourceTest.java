package site.pplee.jcode.agentcore.concurrent;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.util.concurrent.CancellationException;

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
}
