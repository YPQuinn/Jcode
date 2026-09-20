package site.pplee.jcode.codingagent.support;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Deterministic barrier that models a file-system call unaffected by interruption. */
public final class ReloadGate implements AutoCloseable {
    private final CompletableFuture<Void> entered = new CompletableFuture<>();
    private final CompletableFuture<Void> released = new CompletableFuture<>();

    /** Block the loader until the test releases it, preserving any interrupt status. */
    public void awaitRelease() {
        entered.complete(null);
        released.join();
    }

    /** Wait until the loader is inside the simulated file-system call. */
    public void awaitEntered() throws Exception {
        entered.get(5, TimeUnit.SECONDS);
    }

    /** Release the loader even when an assertion fails. */
    @Override
    public void close() {
        released.complete(null);
    }
}
