package site.pplee.jcode.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/** Apply ordered projection events to a snapshot, ignoring repeated delivery. */
public final class SessionReducer {
    public static final int MAX_RUNS = 1_024;
    public static final int MAX_INPUTS = 1_024;
    public static final int MAX_APPROVALS = 256;
    public static final int MAX_MESSAGES = 256;
    public static final int MAX_TOOLS = 256;

    private SessionReducer() {
    }

    public static SessionSnapshot apply(
            SessionSnapshot snapshot,
            SessionEvent event,
            ObjectMapper mapper
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        if (snapshot.schemaVersion() != 1) {
            throw new IllegalArgumentException("unsupported snapshot schemaVersion");
        }
        if (event.schemaVersion() != 1) {
            throw new IllegalArgumentException("unsupported event schemaVersion");
        }
        if (!snapshot.sessionId().equals(event.sessionId())
                || !snapshot.cursor().epoch().equals(event.cursor().epoch())) {
            throw new IllegalArgumentException("event belongs to another session stream");
        }
        if (event.cursor().seq() <= snapshot.cursor().seq()) {
            return snapshot;
        }
        if (event.cursor().seq() != snapshot.cursor().seq() + 1) {
            throw new IllegalArgumentException("event sequence has a gap");
        }
        try {
            return switch (event.type()) {
                case RUN_CHANGED -> new SessionSnapshot(snapshot.sessionId(), event.cursor(),
                        snapshot.leafId(), upsert(snapshot.runs(),
                                mapper.treeToValue(event.data(), RunView.class), RunView::runId,
                                MAX_RUNS),
                        snapshot.inputs(), snapshot.approvals(), snapshot.messages(), snapshot.tools());
                case INPUT_CHANGED -> new SessionSnapshot(snapshot.sessionId(), event.cursor(),
                        snapshot.leafId(), snapshot.runs(), upsert(snapshot.inputs(),
                                mapper.treeToValue(event.data(), InputView.class), InputView::inputId,
                                MAX_INPUTS),
                        snapshot.approvals(), snapshot.messages(), snapshot.tools());
                case APPROVAL_CHANGED -> new SessionSnapshot(snapshot.sessionId(), event.cursor(),
                        snapshot.leafId(), snapshot.runs(), snapshot.inputs(),
                        upsert(snapshot.approvals(), mapper.treeToValue(event.data(), ApprovalView.class),
                                ApprovalView::approvalId, MAX_APPROVALS,
                                approval -> approval.status().terminal()),
                        snapshot.messages(), snapshot.tools());
                case MESSAGE_CHANGED -> {
                    var message = mapper.treeToValue(event.data(), MessageView.class);
                    yield new SessionSnapshot(snapshot.sessionId(), event.cursor(),
                            message.entryId() == null ? snapshot.leafId() : message.entryId(),
                            snapshot.runs(), snapshot.inputs(), snapshot.approvals(),
                            upsert(snapshot.messages(), message, MessageView::messageId,
                                    MAX_MESSAGES), snapshot.tools());
                }
                case TOOL_CHANGED -> new SessionSnapshot(snapshot.sessionId(), event.cursor(),
                        snapshot.leafId(), snapshot.runs(), snapshot.inputs(), snapshot.approvals(),
                        snapshot.messages(), upsert(snapshot.tools(),
                                mapper.treeToValue(event.data(), ToolView.class),
                                tool -> new ToolKey(tool.runId(), tool.toolCallId()), MAX_TOOLS));
                case HISTORY_CHANGED -> new SessionSnapshot(snapshot.sessionId(), event.cursor(),
                        mapper.treeToValue(event.data(), HistoryView.class).leafId(),
                        snapshot.runs(), snapshot.inputs(), snapshot.approvals(),
                        snapshot.messages(), snapshot.tools());
            };
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("event payload is not valid for its type", failure);
        }
    }

    private static <T> List<T> upsert(
            List<T> current,
            T next,
            Function<T, ?> identity,
            int limit
    ) {
        return upsert(current, next, identity, limit, ignored -> true);
    }

    private static <T> List<T> upsert(
            List<T> current,
            T next,
            Function<T, ?> identity,
            int limit,
            Predicate<T> evictable
    ) {
        var updated = new ArrayList<>(current);
        for (int index = 0; index < updated.size(); index++) {
            if (identity.apply(updated.get(index)).equals(identity.apply(next))) {
                updated.set(index, next);
                return updated;
            }
        }
        updated.add(next);
        if (updated.size() > limit) {
            for (int index = 0; index < updated.size(); index++) {
                if (evictable.test(updated.get(index))) {
                    updated.remove(index);
                    return updated;
                }
            }
            throw new IllegalStateException("projection retention contains no evictable entry");
        }
        return updated;
    }

    private record ToolKey(String runId, String toolCallId) { }
}
