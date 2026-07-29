package site.pplee.jcode.ai.concurrent;

/**
 * Read-only cancellation handle shared by model adapters and tools. Exposes
 * only status checks; holders cannot trigger cancellation.
 *
 * <p>Lives in {@code ai} (the lowest shared layer) because Java has no platform
 * equivalent of the browser {@code AbortSignal}; both {@code ModelClient}
 * adapters and {@code AgentTool} executors must read the same cancellation
 * signal. Creating and triggering cancellation ({@code CancellationSource})
 * stays in {@code agent-core}, which owns active-run cancellation.
 */
public interface CancellationSignal {
    /** True once cancellation has been requested. */
    boolean isCancelled();

    /** Throw {@link java.util.concurrent.CancellationException} if cancelled. */
    void throwIfCancelled();
}
