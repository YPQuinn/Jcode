package site.pplee.jcode.agentcore.concurrent;

import site.pplee.jcode.ai.concurrent.CancellationRegistration;
import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Cancellation holder owned by the run. {@link #signal()} returns a private
 * view that cannot be cast back to this class, so consumers can observe
 * cancellation but never trigger it. {@link #cancel()} is idempotent and
 * notifies registered listeners exactly once each.
 *
 * <p>The read-only protocol {@link CancellationSignal} lives in {@code ai}
 * (the lowest shared layer) because model adapters and tools must read the
 * same signal; only the <em>ownership</em> of cancellation stays here in
 * {@code agent-core}, which owns active-run cancellation.
 */
public final class CancellationSource {
    private final Object lock = new Object();
    private volatile boolean cancelled;
    private final Set<Registration> registrations = new LinkedHashSet<>();
    private final CancellationSignal signal = new SignalView();

    /** Return the read-only signal handed to tools and model adapters. */
    public CancellationSignal signal() {
        return signal;
    }

    /** True once cancelled. */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Idempotently request cancellation and notify remaining listeners.
     * Listener failures are isolated so they cannot fail this method or
     * prevent other listeners from running.
     */
    public void cancel() {
        List<Runnable> toNotify = new ArrayList<>();
        synchronized (lock) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            for (Registration registration : registrations) {
                Runnable listener = registration.take();
                if (listener != null) {
                    toNotify.add(listener);
                }
            }
            registrations.clear();
        }
        for (Runnable listener : toNotify) {
            runIsolated(listener);
        }
    }

    /** Throw {@link CancellationException} if cancelled. */
    public void throwIfCancelled() {
        if (cancelled) {
            throw new CancellationException("cancelled");
        }
    }

    private CancellationRegistration register(Runnable listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        boolean runNow;
        Registration registration;
        synchronized (lock) {
            if (cancelled) {
                runNow = true;
                registration = new Registration(null);
            } else {
                runNow = false;
                registration = new Registration(listener);
                registrations.add(registration);
            }
        }
        if (runNow) {
            runIsolated(listener);
        }
        return registration;
    }

    private static void runIsolated(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException ignored) {
            // Listener failures must not fail cancel() or other listeners.
        }
    }

    /**
     * Private view so consumers cannot cast back to {@link CancellationSource}
     * and call {@link #cancel()}.
     */
    private final class SignalView implements CancellationSignal {
        @Override
        public boolean isCancelled() {
            return CancellationSource.this.cancelled;
        }

        @Override
        public void throwIfCancelled() {
            CancellationSource.this.throwIfCancelled();
        }

        @Override
        public CancellationRegistration onCancellation(Runnable listener) {
            return CancellationSource.this.register(listener);
        }
    }

    private final class Registration implements CancellationRegistration {
        private Runnable listener;

        Registration(Runnable listener) {
            this.listener = listener;
        }

        Runnable take() {
            Runnable current = listener;
            listener = null;
            return current;
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (listener != null) {
                    registrations.remove(this);
                    listener = null;
                }
            }
        }
    }
}
