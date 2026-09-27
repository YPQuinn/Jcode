package site.pplee.jcode.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.protocol.ErrorCode;
import site.pplee.jcode.protocol.ApprovalStatus;
import site.pplee.jcode.protocol.ApprovalView;
import site.pplee.jcode.protocol.EventCursor;
import site.pplee.jcode.protocol.RunStatus;
import site.pplee.jcode.protocol.RunView;
import site.pplee.jcode.protocol.SessionReducer;
import site.pplee.jcode.protocol.ToolStatus;
import site.pplee.jcode.protocol.ToolView;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SessionFeedTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void snapshotAndConcurrentSubscriptionReconstructTheSameView() throws Exception {
        var feed = new SessionFeed(new Object(), mapper);
        feed.attach("session-1", null);
        var gate = new CountDownLatch(1);
        var publishing = CompletableFuture.runAsync(() -> {
            await(gate);
            feed.publish(run("run-1"));
        });
        gate.countDown();

        var initial = feed.snapshot();
        try (var subscriber = feed.subscribe(initial.cursor())) {
            publishing.orTimeout(5, TimeUnit.SECONDS).join();
            var reconstructed = initial;
            if (initial.cursor().seq() == 0) {
                var event = subscriber.next(Duration.ofSeconds(5)).orElseThrow();
                reconstructed = SessionReducer.apply(reconstructed, event, mapper);
                assertSame(reconstructed, SessionReducer.apply(reconstructed, event, mapper));
            }
            assertEquals(feed.snapshot(), reconstructed);
        }
    }

    @Test
    void expiredCursorAndSlowSubscriberRequireResynchronization() throws Exception {
        var feed = new SessionFeed(new Object(), mapper);
        feed.attach("session-1", null);
        var first = feed.snapshot().cursor();
        try (var slow = feed.subscribe(first)) {
            for (int index = 0; index < SessionFeed.MAX_REPLAY_EVENTS + 1; index++) {
                feed.publish(run("run-" + index));
            }
            assertEquals(ErrorCode.SUBSCRIBER_SLOW, assertThrows(ApiException.class,
                    () -> slow.next(Duration.ZERO)).error().code());
            assertEquals(ErrorCode.CURSOR_EXPIRED, assertThrows(ApiException.class,
                    () -> feed.subscribe(first)).error().code());
            assertEquals(ErrorCode.EPOCH_CHANGED, assertThrows(ApiException.class,
                    () -> feed.subscribe(new EventCursor("another-epoch", 0))).error().code());
        }

        for (int index = SessionFeed.MAX_REPLAY_EVENTS + 1;
                index < SessionReducer.MAX_RUNS; index++) {
            feed.publish(run("run-" + index));
        }
        var beforeEviction = feed.snapshot();
        try (var current = feed.subscribe(beforeEviction.cursor())) {
            feed.publish(run("run-next"));
            var next = current.next(Duration.ofSeconds(5)).orElseThrow();
            assertEquals(SessionReducer.MAX_RUNS, feed.snapshot().runs().size());
            assertEquals(feed.snapshot(), SessionReducer.apply(beforeEviction, next, mapper));
        }
    }

    @Test
    void toolSnapshotsReplaceEarlierOutputForLiveAndReplayedClients() throws Exception {
        var feed = new SessionFeed(new Object(), mapper);
        feed.attach("session-1", null);
        var before = feed.snapshot();
        try (var subscriber = feed.subscribe(before.cursor())) {
            feed.publish(new ToolView("tool-1", "run-1", "bash", ToolStatus.PREPARING,
                    "first", false, false));
            feed.publish(new ToolView("tool-1", "run-1", "bash", ToolStatus.PREPARING,
                    "second", false, false));
            var local = before;
            for (int index = 0; index < 2; index++) {
                local = SessionReducer.apply(local,
                        subscriber.next(Duration.ofSeconds(5)).orElseThrow(), mapper);
            }
            assertEquals(1, local.tools().size());
            assertEquals("second", local.tools().getFirst().outputTail());
            assertEquals(feed.snapshot(), local);
        }
    }

    @Test
    void serverCloseDrainsAcceptedEventsBeforeReportingClosure() throws Exception {
        var feed = new SessionFeed(new Object(), mapper);
        feed.attach("session-1", null);
        var subscriber = feed.subscribe(feed.snapshot().cursor());
        try {
            feed.publish(run("run-1"));
            feed.publish(new RunView("session-1", "command-run-1", "run-1",
                    RunStatus.COMPLETED, false, "STOP", "done", false, null));
            feed.close();

            assertEquals(RunStatus.ACCEPTED, mapper.treeToValue(
                    subscriber.next(Duration.ZERO).orElseThrow().data(), RunView.class).status());
            assertEquals(RunStatus.COMPLETED, mapper.treeToValue(
                    subscriber.next(Duration.ZERO).orElseThrow().data(), RunView.class).status());
            assertEquals(ErrorCode.SESSION_CLOSED, assertThrows(ApiException.class,
                    () -> subscriber.next(Duration.ZERO)).error().code());
        } finally {
            subscriber.close();
        }

        var other = new SessionFeed(new Object(), mapper);
        other.attach("session-2", null);
        var clientClosed = other.subscribe(other.snapshot().cursor());
        other.publish(run("run-2"));
        clientClosed.close();
        assertTrue(clientClosed.next(Duration.ZERO).isEmpty());
    }

    @Test
    void serverCloseDrainsAFullSubscriberQueueWithoutNeedingAClosingMarker() throws Exception {
        var feed = new SessionFeed(new Object(), mapper);
        feed.attach("session-1", null);
        try (var subscriber = feed.subscribe(feed.snapshot().cursor())) {
            for (int index = 0; index < SessionSubscription.CAPACITY; index++) {
                feed.publish(run("run-" + index));
            }
            feed.close();
            for (int index = 0; index < SessionSubscription.CAPACITY; index++) {
                assertEquals("run-" + index, mapper.treeToValue(
                        subscriber.next(Duration.ZERO).orElseThrow().data(), RunView.class).runId());
            }
            assertEquals(ErrorCode.SESSION_CLOSED, assertThrows(ApiException.class,
                    () -> subscriber.next(Duration.ZERO)).error().code());
        }
    }

    @Test
    void toolIdentityUsesBothOpaqueIdsForLiveAndReplayedClients() throws Exception {
        var feed = new SessionFeed(new Object(), mapper);
        feed.attach("session-1", null);
        var before = feed.snapshot();
        try (var live = feed.subscribe(before.cursor())) {
            feed.publish(new ToolView("b", "run:a", "read", ToolStatus.COMPLETED,
                    "first", false, false));
            feed.publish(new ToolView("a:b", "run", "bash", ToolStatus.PREPARING,
                    "second", false, false));

            var liveView = SessionReducer.apply(before,
                    live.next(Duration.ZERO).orElseThrow(), mapper);
            liveView = SessionReducer.apply(liveView,
                    live.next(Duration.ZERO).orElseThrow(), mapper);
            assertEquals(2, liveView.tools().size());
            assertEquals(feed.snapshot(), liveView);
            assertEquals("first", feed.tool("run:a", "b").outputTail());
            assertEquals("second", feed.tool("run", "a:b").outputTail());
        }

        try (var replayed = feed.subscribe(before.cursor())) {
            var replayView = before;
            for (int index = 0; index < 2; index++) {
                replayView = SessionReducer.apply(replayView,
                        replayed.next(Duration.ZERO).orElseThrow(), mapper);
            }
            assertEquals(feed.snapshot(), replayView);
        }
    }

    @Test
    void projectionRetentionKeepsPendingApprovalWhileOldDecisionsExpire() throws Exception {
        var feed = new SessionFeed(new Object(), mapper);
        feed.attach("session-1", null);
        feed.publish(approval("pending", ApprovalStatus.PENDING));
        for (int index = 0; index < SessionReducer.MAX_APPROVALS - 1; index++) {
            feed.publish(approval("done-" + index, ApprovalStatus.ALLOWED));
        }
        var before = feed.snapshot();
        try (var subscriber = feed.subscribe(before.cursor())) {
            feed.publish(approval("new", ApprovalStatus.DENIED));
            var event = subscriber.next(Duration.ofSeconds(5)).orElseThrow();
            assertEquals(feed.snapshot(), SessionReducer.apply(before, event, mapper));
            assertEquals(SessionReducer.MAX_APPROVALS, feed.snapshot().approvals().size());
            assertTrue(feed.snapshot().approvals().stream()
                    .anyMatch(item -> item.approvalId().equals("pending")));
        }
    }

    private static ApprovalView approval(String id, ApprovalStatus status) {
        return new ApprovalView("session-1", id, "run-1", "tool-" + id,
                "read", "digest-" + id, "{}", status);
    }

    private static RunView run(String id) {
        return new RunView("session-1", "command-" + id, id,
                RunStatus.ACCEPTED, false, null, null, false, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
