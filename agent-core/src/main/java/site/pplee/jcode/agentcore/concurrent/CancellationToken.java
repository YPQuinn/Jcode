package site.pplee.jcode.agentcore.concurrent;

/**
 * Read-only cancellation handle handed to tools and model adapters. Exposes
 * only status checks; holders cannot trigger cancellation.
 */
public interface CancellationToken {
    boolean isCancelled();

    void throwIfCancelled();
}
