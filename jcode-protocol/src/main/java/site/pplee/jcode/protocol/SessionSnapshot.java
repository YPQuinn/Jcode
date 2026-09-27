package site.pplee.jcode.protocol;

import java.util.List;
import java.util.Objects;

/** Consistent in-memory view and cursor for reconnecting to one live service process. */
public record SessionSnapshot(
        int schemaVersion,
        String sessionId,
        EventCursor cursor,
        String leafId,
        List<RunView> runs,
        List<InputView> inputs,
        List<ApprovalView> approvals,
        List<MessageView> messages,
        List<ToolView> tools
) {
    public SessionSnapshot {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("unsupported snapshot schemaVersion");
        }
        ProtocolIds.require(sessionId, "sessionId");
        Objects.requireNonNull(cursor, "cursor must not be null");
        runs = List.copyOf(Objects.requireNonNull(runs, "runs must not be null"));
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs must not be null"));
        approvals = List.copyOf(Objects.requireNonNull(approvals, "approvals must not be null"));
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
    }

    public SessionSnapshot(
            String sessionId,
            EventCursor cursor,
            String leafId,
            List<RunView> runs,
            List<InputView> inputs,
            List<ApprovalView> approvals,
            List<MessageView> messages,
            List<ToolView> tools
    ) {
        this(1, sessionId, cursor, leafId, runs, inputs, approvals, messages, tools);
    }
}
