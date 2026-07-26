package site.pplee.jcode.agentcore.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationSource {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CancellationToken token = new TokenView();

    public CancellationToken token() {
        return token;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
    }

    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancellationException("cancelled");
        }
    }

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
