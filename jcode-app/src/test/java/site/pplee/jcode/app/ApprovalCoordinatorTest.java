package site.pplee.jcode.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.concurrent.CancellationRegistration;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.codingagent.tool.CodingToolRequest;
import site.pplee.jcode.protocol.ApprovalCommand;
import site.pplee.jcode.protocol.ApprovalDecision;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalCoordinatorTest {
    private static final CancellationSignal NEVER_CANCELLED = new CancellationSignal() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void throwIfCancelled() { }

        @Override
        public CancellationRegistration onCancellation(Runnable listener) {
            return () -> { };
        }
    };

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void terminalDecisionsRemoveTheirScheduledTimeouts() {
        var timeouts = executor();
        try {
            var lock = new Object();
            var feed = feed(lock);
            var approvals = new ApprovalCoordinator(lock, feed, "session-1",
                    settings(), timeouts);
            for (int index = 0; index < 3; index++) {
                approvals.request(request("tool-" + index), NEVER_CANCELLED, () -> "run-1");
                var pending = feed.snapshot().approvals().getLast();
                if (index < 2) {
                    approvals.resolve(new ApprovalCommand(pending.approvalId(),
                            pending.toolCallId(), pending.requestDigest(),
                            index == 0 ? ApprovalDecision.ALLOW : ApprovalDecision.DENY));
                } else {
                    java.util.List<Runnable> completions;
                    synchronized (lock) {
                        completions = approvals.markCancelled("run-1");
                    }
                    assertEquals(1, completions.size());
                    completions.forEach(Runnable::run);
                }
                assertTrue(timeouts.getQueue().isEmpty(),
                        "settled approval must release its one-hour timeout");
            }
        } finally {
            timeouts.shutdownNow();
        }
    }

    @Test
    void settlementBeforeTimeoutHandleRegistrationStillCancelsTheTask() throws Exception {
        var timeouts = new PausingExecutor();
        timeouts.setRemoveOnCancelPolicy(true);
        try {
            var lock = new Object();
            var feed = feed(lock);
            var approvals = new ApprovalCoordinator(lock, feed, "session-1",
                    settings(), timeouts);
            var requesting = CompletableFuture.runAsync(() ->
                    approvals.request(request("tool-1"), NEVER_CANCELLED, () -> "run-1"));
            assertTrue(timeouts.scheduled.await(5, TimeUnit.SECONDS));
            var pending = feed.snapshot().approvals().getFirst();
            approvals.resolve(new ApprovalCommand(pending.approvalId(),
                    pending.toolCallId(), pending.requestDigest(), ApprovalDecision.ALLOW));
            assertEquals(1, timeouts.getQueue().size());

            timeouts.release.countDown();
            requesting.orTimeout(5, TimeUnit.SECONDS).join();
            assertTrue(timeouts.getQueue().isEmpty());
        } finally {
            timeouts.release.countDown();
            timeouts.shutdownNow();
        }
    }

    private SessionFeed feed(Object lock) {
        var feed = new SessionFeed(lock, mapper);
        feed.attach("session-1", null);
        return feed;
    }

    private CodingToolRequest request(String toolCallId) {
        return new CodingToolRequest(toolCallId, "read",
                mapper.createObjectNode().put("path", "file.txt"), Path.of("."));
    }

    private static ApprovalSettings settings() {
        return new ApprovalSettings(Set.of("read"), Duration.ofHours(1));
    }

    private static ScheduledThreadPoolExecutor executor() {
        var executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static final class PausingExecutor extends ScheduledThreadPoolExecutor {
        private final CountDownLatch scheduled = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private PausingExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            var future = super.schedule(command, delay, unit);
            scheduled.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timeout registration was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return future;
        }
    }
}
