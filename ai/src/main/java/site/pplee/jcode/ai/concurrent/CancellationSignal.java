package site.pplee.jcode.ai.concurrent;

/**
 * Read-only cancellation handle shared by model adapters and tools. Exposes
 * only status checks and observer registration; holders cannot trigger
 * cancellation.
 *
 * <p>Lives in {@code ai} (the lowest shared layer) because Java has no platform
 * equivalent of the browser {@code AbortSignal}; both {@code ModelClient}
 * adapters and {@code AgentTool} executors must read the same cancellation
 * signal. Creating and triggering cancellation ({@code CancellationSource})
 * stays in {@code agent-core}, which owns active-run cancellation.
 *
 * <p>{@link #onCancellation(Runnable)} observes future cancellation. The
 * listener must be non-blocking: mark state, complete or cancel a future,
 * close a body, or push to a queue. It must not wait, perform network I/O,
 * or invoke a user callback that may block. Listeners run on the thread that
 * calls {@code cancel()} (typically the {@code Agent.abort()} caller).
 */
public interface CancellationSignal {
    /** True once cancellation has been requested. */
    boolean isCancelled();

    /** Throw {@link java.util.concurrent.CancellationException} if cancelled. */
    void throwIfCancelled();

    /**
     * Register a listener that runs once when cancellation is requested.
     * If this signal is already cancelled, the listener runs before this
     * method returns. Closing the returned registration before cancellation
     * is linearized prevents the listener from running.
     */
    CancellationRegistration onCancellation(Runnable listener);
}
