package site.pplee.jcode.codingagent.session;

import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.message.AgentMessage;

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
            }
        }
        return new SessionContext(
                messages, Optional.ofNullable(model), Optional.ofNullable(thinkingLevel));
    }
}
