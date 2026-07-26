package site.pplee.jcode.agentcore.concurrent;

import org.junit.jupiter.api.Test;

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
        assertFalse(source.token().isCancelled());
    }

    @Test
    void cancelFlipsState() {
        var source = new CancellationSource();
        source.cancel();
        assertTrue(source.isCancelled());
        assertTrue(source.token().isCancelled());
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
        source.token().throwIfCancelled();
    }

    @Test
    void throwIfCancelledThrowsWhenCancelled() {
        var source = new CancellationSource();
        source.cancel();

        assertThrows(CancellationException.class, source::throwIfCancelled);
        assertThrows(CancellationException.class, () -> source.token().throwIfCancelled());
    }

    @Test
    void tokenIsStableReference() {
        var source = new CancellationSource();
        var first = source.token();
        var second = source.token();
        assertSame(first, second);
    }

    @Test
    void tokenReflectsCancellation() {
        var source = new CancellationSource();
        var token = source.token();
        assertFalse(token.isCancelled());
        source.cancel();
        assertTrue(token.isCancelled());
    }

    @Test
    void tokenIsNotACancellationSource() {
        var token = new CancellationSource().token();
        // The token is a private TokenView. Static types are inconvertible
        // (so `token instanceof CancellationSource` won't even compile), and
        // at runtime the token object is not a CancellationSource instance.
        assertFalse(CancellationSource.class.isInstance(token));
        assertFalse(CancellationSource.class.isAssignableFrom(token.getClass()));
    }
}
