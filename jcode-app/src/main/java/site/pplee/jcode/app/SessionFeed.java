package site.pplee.jcode.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.protocol.ApprovalView;
import site.pplee.jcode.protocol.ErrorCode;
import site.pplee.jcode.protocol.EventCursor;
import site.pplee.jcode.protocol.EventType;
import site.pplee.jcode.protocol.HistoryView;
import site.pplee.jcode.protocol.InputView;
import site.pplee.jcode.protocol.MessageView;
import site.pplee.jcode.protocol.RunView;
import site.pplee.jcode.protocol.SessionEvent;
import site.pplee.jcode.protocol.SessionReducer;
import site.pplee.jcode.protocol.SessionSnapshot;
import site.pplee.jcode.protocol.ToolView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Ordered projection, bounded replay, and listener admission under one session lock. */
final class SessionFeed {
    static final int MAX_REPLAY_EVENTS = 512;
    static final int MAX_REPLAY_BYTES = 1_048_576;
    static final int MAX_SUBSCRIPTIONS = 32;

    private final Object lock;
    private final ObjectMapper mapper;
    private final String epoch = UUID.randomUUID().toString();
    private final ArrayDeque<SessionEvent> replay = new ArrayDeque<>();
    private final Set<SessionSubscription> subscribers = new LinkedHashSet<>();
    private final Map<String, RunView> runs = new LinkedHashMap<>();
    private final Map<String, InputView> inputs = new LinkedHashMap<>();
    private final Map<String, ApprovalView> approvals = new LinkedHashMap<>();
    private final Map<String, MessageView> messages = new LinkedHashMap<>();
    private final Map<String, ToolView> tools = new LinkedHashMap<>();
    private String sessionId;
    private String leafId;
    private long seq;
    private int replayBytes;
    private boolean closed;

    SessionFeed(Object lock, ObjectMapper mapper) {
        this.lock = Objects.requireNonNull(lock);
        this.mapper = Objects.requireNonNull(mapper);
    }

    void attach(String sessionId, String leafId) {
        synchronized (lock) {
            this.sessionId = Objects.requireNonNull(sessionId);
            this.leafId = leafId;
        }
    }

    SessionSnapshot snapshot() {
        synchronized (lock) {
            return new SessionSnapshot(sessionId, cursor(), leafId,
                    new ArrayList<>(runs.values()), new ArrayList<>(inputs.values()),
                    new ArrayList<>(approvals.values()), new ArrayList<>(messages.values()),
                    new ArrayList<>(tools.values()));
        }
    }

    SessionSubscription subscribe(EventCursor after) {
        Objects.requireNonNull(after, "after must not be null");
        synchronized (lock) {
            if (closed) {
                throw new ApiException(ErrorCode.SESSION_CLOSED, "session stream is closed");
            }
            if (!epoch.equals(after.epoch())) {
                throw new ApiException(ErrorCode.EPOCH_CHANGED, "session stream has a new epoch");
            }
            long earliest = replay.isEmpty() ? seq + 1 : replay.getFirst().cursor().seq();
            if (after.seq() < earliest - 1) {
                throw new ApiException(ErrorCode.CURSOR_EXPIRED, "event cursor is outside replay retention");
            }
            if (after.seq() > seq) {
                throw new ApiException(ErrorCode.STATE_CONFLICT, "event cursor is ahead of this session");
            }
            if (subscribers.size() >= MAX_SUBSCRIPTIONS) {
                throw new ApiException(ErrorCode.CAPACITY_EXCEEDED, "session subscriber limit reached");
            }
            var subscription = new SessionSubscription(this);
            int pending = 0;
            for (var event : replay) {
                if (event.cursor().seq() > after.seq()) {
                    if (++pending > SessionSubscription.CAPACITY) {
                        throw new ApiException(ErrorCode.CURSOR_EXPIRED,
                                "replay exceeds subscriber capacity; take a fresh snapshot");
                    }
                    subscription.offer(event);
                }
            }
            subscribers.add(subscription);
            return subscription;
        }
    }

    void unsubscribe(SessionSubscription subscription) {
        synchronized (lock) {
            subscribers.remove(subscription);
        }
    }

    void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            subscribers.forEach(SessionSubscription::finish);
            subscribers.clear();
        }
    }

    void publish(RunView view) {
        publish(view.runId(), EventType.RUN_CHANGED, view);
    }

    void publish(InputView view) {
        publish(view.targetRunId(), EventType.INPUT_CHANGED, view);
    }

    void publish(ApprovalView view) {
        publish(view.runId(), EventType.APPROVAL_CHANGED, view);
    }

    void publish(MessageView view) {
        publish(view.runId(), EventType.MESSAGE_CHANGED, view);
    }

    void publish(ToolView view) {
        publish(view.runId(), EventType.TOOL_CHANGED, view);
    }

    void publish(HistoryView view) {
        publish(null, EventType.HISTORY_CHANGED, view);
    }

    ToolView tool(String runId, String toolCallId) {
        synchronized (lock) {
            return tools.get(runId + ':' + toolCallId);
        }
    }

    private void publish(String runId, EventType type, Object view) {
        var data = mapper.valueToTree(view);
        synchronized (lock) {
            if (view instanceof RunView run) {
                var previous = runs.get(run.runId());
                if (run.equals(previous) || (previous != null && previous.status().terminal())) {
                    return;
                }
            }
            if (view instanceof InputView input) {
                var previous = inputs.get(input.inputId());
                if (input.equals(previous) || (previous != null
                        && previous.status() != site.pplee.jcode.protocol.InputStatus.PENDING)) {
                    return;
                }
            }
            if (view instanceof ApprovalView approval) {
                var previous = approvals.get(approval.approvalId());
                if (approval.equals(previous) || (previous != null && previous.status().terminal())) {
                    return;
                }
            }
            if (view instanceof MessageView message) {
                var previous = messages.get(message.messageId());
                if (message.equals(previous) || (previous != null && previous.complete())) {
                    return;
                }
            }
            if (view instanceof ToolView tool) {
                var previous = tools.get(tool.runId() + ':' + tool.toolCallId());
                if (tool.equals(previous) || (previous != null
                        && previous.status() == site.pplee.jcode.protocol.ToolStatus.COMPLETED)) {
                    return;
                }
            }
            var event = new SessionEvent(sessionId, runId, new EventCursor(epoch, ++seq), type, data);
            switch (view) {
                case RunView run -> {
                    runs.put(run.runId(), run);
                    trim(runs, SessionReducer.MAX_RUNS, ignored -> true);
                }
                case InputView input -> {
                    inputs.put(input.inputId(), input);
                    trim(inputs, SessionReducer.MAX_INPUTS, ignored -> true);
                }
                case ApprovalView approval -> {
                    approvals.put(approval.approvalId(), approval);
                    trim(approvals, SessionReducer.MAX_APPROVALS,
                            item -> item.status().terminal());
                }
                case MessageView message -> {
                    messages.put(message.messageId(), message);
                    trim(messages, SessionReducer.MAX_MESSAGES, ignored -> true);
                    if (message.entryId() != null) {
                        leafId = message.entryId();
                    }
                }
                case ToolView tool -> {
                    tools.put(tool.runId() + ':' + tool.toolCallId(), tool);
                    trim(tools, SessionReducer.MAX_TOOLS, ignored -> true);
                }
                case HistoryView history -> leafId = history.leafId();
                default -> throw new IllegalArgumentException("unsupported projection view");
            }
            replay.addLast(event);
            replayBytes += estimatedBytes(event);
            while (replay.size() > MAX_REPLAY_EVENTS || replayBytes > MAX_REPLAY_BYTES) {
                replayBytes -= estimatedBytes(replay.removeFirst());
            }
            for (var iterator = subscribers.iterator(); iterator.hasNext();) {
                var subscriber = iterator.next();
                if (!subscriber.offer(event)) {
                    subscriber.expire();
                    iterator.remove();
                }
            }
        }
    }

    private EventCursor cursor() {
        return new EventCursor(epoch, seq);
    }

    private static <T> void trim(Map<String, T> entries, int limit, Predicate<T> evictable) {
        if (entries.size() <= limit) {
            return;
        }
        for (var iterator = entries.entrySet().iterator(); iterator.hasNext();) {
            if (evictable.test(iterator.next().getValue())) {
                iterator.remove();
                return;
            }
        }
        throw new IllegalStateException("projection retention contains no evictable entry");
    }

    private static int estimatedBytes(SessionEvent event) {
        return 96 + event.data().toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
