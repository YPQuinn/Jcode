package site.pplee.jcode.ai.stream;

import site.pplee.jcode.ai.message.Message;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * First-class stream of {@link AssistantMessageEvent}s from a model adapter.
 * Producers (model adapters) push events; consumers (the agent loop) pull
 * them via blocking {@link #take()}.
 *
 * <p>Contract:
 * <ul>
 *   <li>Success, model errors, network errors, and active cancellation all
 *       produce a final {@link Message.Assistant} via the stream protocol
 *       ({@link AssistantMessageEvent.Done} or {@link AssistantMessageEvent.Error}).
 *   <li>Only protocol implementation bugs throw from {@link #take()}
 *       (i.e. {@link InterruptedException} when the consuming thread is
 *       interrupted).
 *   <li>{@link #push} after a terminal event ({@code Done}/{@code Error}) is
 *       silently ignored.
 * </ul>
 *
 * <p>The stream is safe for single-consumer use. {@link #push} linearizes
 * enqueue, the {@code done} flag, and {@link #resultStage()} completion so
 * concurrent producers admit at most one terminal event and cannot enqueue
 * a delta after that terminal.
 */
public final class AssistantMessageStream {
    private final LinkedBlockingQueue<AssistantMessageEvent> queue = new LinkedBlockingQueue<>();
    private final CompletableFuture<Message.Assistant> resultFuture = new CompletableFuture<>();
    private final Object lock = new Object();
    private volatile boolean done;

    /**
     * Push an event. Called by the model adapter (producer side).
     * After a terminal event ({@link AssistantMessageEvent.Done} or
     * {@link AssistantMessageEvent.Error}), subsequent pushes are silently
     * ignored. Checking {@code done}, enqueue, and completing the result
     * stage are one linearizable step.
     */
    public void push(AssistantMessageEvent event) {
        synchronized (lock) {
            if (done) {
                return;
            }
            if (event instanceof AssistantMessageEvent.Done d) {
                queue.add(event);
                done = true;
                resultFuture.complete(d.message());
            } else if (event instanceof AssistantMessageEvent.Error e) {
                queue.add(event);
                done = true;
                resultFuture.complete(e.error());
            } else {
                queue.add(event);
            }
        }
    }

    /**
     * Pull the next event, blocking until one is available. Called by the
     * agent loop (consumer side) on a virtual thread.
     *
     * @return the next event, or {@code null} if the stream is done and
     *         all events (including the terminal event) have been consumed
     * @throws InterruptedException if the calling thread is interrupted
     *         while waiting
     */
    public AssistantMessageEvent take() throws InterruptedException {
        if (done && queue.isEmpty()) {
            return null;
        }
        return queue.take();
    }

    /** True once a terminal event ({@code Done}/{@code Error}) has been pushed. */
    public boolean isDone() {
        return done;
    }

    /**
     * CompletionStage that completes with the final
     * {@link Message.Assistant} after {@code Done} or {@code Error} is
     * pushed. Completes exceptionally only on protocol bugs.
     */
    public CompletionStage<Message.Assistant> resultStage() {
        return resultFuture;
    }

    /**
     * Block until the final {@link Message.Assistant} is available. Throws
     * {@link java.util.concurrent.CompletionException} if the result stage
     * completes exceptionally (protocol bug only).
     */
    public Message.Assistant result() {
        return resultFuture.join();
    }
}
