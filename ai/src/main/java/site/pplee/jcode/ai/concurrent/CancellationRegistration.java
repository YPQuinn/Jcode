package site.pplee.jcode.ai.concurrent;

/**
 * Registration returned by {@link CancellationSignal#onCancellation(Runnable)}.
 * Closing it unregisters the listener if cancellation has not already been
 * linearized. Close is idempotent.
 */
@FunctionalInterface
public interface CancellationRegistration extends AutoCloseable {
    @Override
    void close();
}
