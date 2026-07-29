package site.pplee.jcode.agentcore.concurrent;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellation holder owned by the run. {@link #signal()} returns a private
 * view that cannot be cast back to this class, so consumers can observe
 * cancellation but never trigger it. Backed by an {@link AtomicBoolean};
 * {@link #cancel()} is idempotent.
 *
 * <p>The read-only protocol {@link CancellationSignal} lives in {@code ai}
 * (the lowest shared layer) because model adapters and tools must read the
 * same signal; only the <em>ownership</em> of cancellation stays here in
 * {@code agent-core}, which owns active-run cancellation.
 */
public final class CancellationSource {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CancellationSignal signal = new SignalView();

    /** Return the read-only signal handed to tools and model adapters. */
    public CancellationSignal signal() {
        return signal;
    }

    /** True once cancelled. */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /** Idempotently request cancellation. */
    public void cancel() {
        cancelled.set(true);
    }

    /** Throw {@link CancellationException} if cancelled. */
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancellationException("cancelled");
        }
    }

    /**
     * Private view so consumers cannot cast back to {@link CancellationSource}
     * and call {@link #cancel()}.
     */
    private final class SignalView implements CancellationSignal {
        @Override
        public boolean isCancelled() {
            return CancellationSource.this.cancelled.get();
        }

        @Override
        public void throwIfCancelled() {
            CancellationSource.this.throwIfCancelled();
        }
    }
}
