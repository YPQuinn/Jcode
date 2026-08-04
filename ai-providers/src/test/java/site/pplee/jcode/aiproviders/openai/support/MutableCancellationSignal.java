package site.pplee.jcode.aiproviders.openai.support;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only {@link CancellationSignal} whose cancelled state can be flipped
 * by tests.
 */
public final class MutableCancellationSignal implements CancellationSignal {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /** Request cancellation (idempotent). */
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancellationException("cancelled");
        }
    }
}
