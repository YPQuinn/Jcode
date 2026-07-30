package site.pplee.jcode.agentcore;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.event.AgentEventSink;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEventEmitterTest {

    @Test
    void emitBlocksUntilSinkStageCompletes() throws InterruptedException {
        var stage = new CompletableFuture<Void>();
        var emitReached = new CountDownLatch(1);
        var emitReturned = new CountDownLatch(1);
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                emitReached.countDown();
                return stage;
            }
        };
        var emitter = new RunEventEmitter(sink);

        var thread = Thread.startVirtualThread(() -> {
            emitter.emit(new AgentEvent.AgentStarted());
            emitReturned.countDown();
        });

        assertTrue(emitReached.await(2, TimeUnit.SECONDS),
                "emit must reach the sink");
        assertFalse(emitReturned.await(200, TimeUnit.MILLISECONDS),
                "emit must not return while the sink stage is incomplete");

        stage.complete(null);
        assertTrue(emitReturned.await(2, TimeUnit.SECONDS),
                "emit must return after the sink stage completes");
        thread.join();
    }

    @Test
    void failedSinkStagePropagatesAsCompletionException() {
        var sink = new AgentEventSink() {
            @Override
            public CompletionStage<Void> emit(AgentEvent event) {
                return CompletableFuture.failedFuture(new RuntimeException("boom"));
            }
        };
        var emitter = new RunEventEmitter(sink);
        assertThrows(CompletionException.class,
                () -> emitter.emit(new AgentEvent.AgentStarted()));
    }

    @Test
    void nullDelegateAndNoopAreNonBlocking() {
        var event = new AgentEvent.AgentStarted();
        var nullEmitter = new RunEventEmitter(null);
        var noopEmitter = RunEventEmitter.noop();
        assertDoesNotThrow(() -> nullEmitter.emit(event));
        assertDoesNotThrow(() -> noopEmitter.emit(event));
    }
}
