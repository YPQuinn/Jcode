package site.pplee.jcode.aiproviders.openai.support;

import site.pplee.jcode.ai.concurrent.CancellationRegistration;
import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Test-only {@link CancellationSignal} whose cancelled state can be flipped
 * by tests. Listener registration matches the production contract.
 */
public final class MutableCancellationSignal implements CancellationSignal {
    private final Object lock = new Object();
    private volatile boolean cancelled;
    private final Set<Registration> registrations = new LinkedHashSet<>();

    /** Request cancellation (idempotent) and notify remaining listeners. */
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

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void throwIfCancelled() {
        if (cancelled) {
            throw new CancellationException("cancelled");
        }
    }

    @Override
    public CancellationRegistration onCancellation(Runnable listener) {
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
            // Match production isolation so tests can exercise failing listeners.
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
