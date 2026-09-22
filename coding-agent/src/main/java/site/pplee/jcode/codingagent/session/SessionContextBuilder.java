package site.pplee.jcode.codingagent.session;

import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure branch projection from session entries to the runtime transcript view. */
public final class SessionContextBuilder {
    private SessionContextBuilder() {
    }

    /** Build context for the snapshot's current leaf. */
    public static SessionContext build(SessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return buildEntries(snapshot.currentBranch());
    }

    /** Build context for an explicitly selected entry without changing the snapshot. */
    public static SessionContext build(SessionSnapshot snapshot, String entryId) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return buildEntries(snapshot.branch(entryId));
    }

    /** Build the effective model-request view for the snapshot's current branch. */
    public static SessionContext buildRequestView(SessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return buildRequestEntries(snapshot.currentBranch());
    }

    /** Build the effective model-request view for an explicitly selected branch. */
    public static SessionContext buildRequestView(SessionSnapshot snapshot, String entryId) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return buildRequestEntries(snapshot.branch(entryId));
    }

    private static SessionContext buildEntries(List<SessionEntry> branch) {
        var messages = new ArrayList<AgentMessage>();
        ModelRef model = null;
        ThinkingLevel thinkingLevel = null;
        for (var entry : branch) {
            switch (entry) {
                case SessionMessageEntry message -> messages.add(message.message());
                case ModelChangeEntry modelChange -> model = modelChange.model();
                case ThinkingLevelChangeEntry thinkingChange ->
                        thinkingLevel = thinkingChange.thinkingLevel();
                case SessionInfoEntry ignored -> {
                    // Global display metadata never enters the model transcript.
                }
                case LabelEntry ignored -> {
                    // Labels annotate display nodes and never enter the transcript.
                }
                case CompactionEntry ignored -> {
                    // Checkpoints affect request projection, never the raw transcript.
                }
                case BranchSummaryEntry ignored -> {
                    // Branch summaries are request-only synthetic context.
                }
            }
        }
        return new SessionContext(
                messages, Optional.ofNullable(model), Optional.ofNullable(thinkingLevel));
    }

    private static SessionContext buildRequestEntries(List<SessionEntry> branch) {
        int checkpointIndex = -1;
        for (int index = 0; index < branch.size(); index++) {
            if (branch.get(index) instanceof CompactionEntry) {
                checkpointIndex = index;
            }
        }

        var messages = new ArrayList<AgentMessage>();
        int retainedStart = 0;
        if (checkpointIndex >= 0) {
            var checkpoint = (CompactionEntry) branch.get(checkpointIndex);
            messages.add(summaryMessage("compaction", checkpoint.id(), checkpoint.summary(), checkpoint.timestamp()));
            retainedStart = indexOf(branch, checkpoint.firstKeptEntryId());
            appendVisible(branch, retainedStart, checkpointIndex, messages);
            appendVisible(branch, checkpointIndex + 1, branch.size(), messages);
        } else {
            appendVisible(branch, 0, branch.size(), messages);
        }

        var raw = buildEntries(branch);
        return new SessionContext(messages, raw.model(), raw.thinkingLevel());
    }

    private static void appendVisible(
            List<SessionEntry> branch,
            int from,
            int to,
            List<AgentMessage> messages
    ) {
        for (int index = from; index < to; index++) {
            switch (branch.get(index)) {
                case SessionMessageEntry message -> messages.add(message.message());
                case BranchSummaryEntry summary -> messages.add(summaryMessage(
                        "branch-summary", summary.id(), summary.summary(), summary.timestamp()));
                default -> {
                    // Configuration, display metadata, and older checkpoints are not messages.
                }
            }
        }
    }

    private static int indexOf(List<SessionEntry> branch, String id) {
        for (int index = 0; index < branch.size(); index++) {
            if (branch.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("entry is not on the selected branch: " + id);
    }

    private static StandardAgentMessage summaryMessage(
            String kind,
            String id,
            String summary,
            java.time.Instant timestamp
    ) {
        String text = "<session_" + kind + " source_entry_id=\"" + id + "\">\n"
                + summary + "\n</session_" + kind + ">";
        return StandardAgentMessage.of(new Message.User(List.of(new Content.Text(text)), timestamp));
    }
}
