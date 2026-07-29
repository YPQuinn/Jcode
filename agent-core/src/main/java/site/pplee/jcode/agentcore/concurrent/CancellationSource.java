package site.pplee.jcode.agentcore.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellation holder owned by the run. {@link #token()} returns a private
 * view that cannot be cast back to this class, so consumers can observe
 * cancellation but never trigger it. Backed by an {@link AtomicBoolean};
 * {@link #cancel()} is idempotent.
 */
public final class CancellationSource {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CancellationToken token = new TokenView();

    /** Return the read-only token handed to tools and adapters. */
    public CancellationToken token() {
        return token;
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
    private final class TokenView implements CancellationToken {
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
