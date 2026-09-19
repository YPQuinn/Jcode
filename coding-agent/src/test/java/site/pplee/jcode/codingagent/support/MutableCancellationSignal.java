package site.pplee.jcode.codingagent.support;

import site.pplee.jcode.ai.concurrent.CancellationRegistration;
import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MutableCancellationSignal implements CancellationSignal {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            listeners.forEach(Runnable::run);
            listeners.clear();
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("cancelled");
        }
    }

    @Override
    public CancellationRegistration onCancellation(Runnable listener) {
        if (isCancelled()) {
            listener.run();
            return () -> { };
        }
        listeners.add(listener);
        if (isCancelled() && listeners.remove(listener)) {
            listener.run();
        }
        return () -> listeners.remove(listener);
    }
}
