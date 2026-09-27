package site.pplee.jcode.codingagent;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.codingagent.session.BranchSummaryEntry;
import site.pplee.jcode.codingagent.session.CompactionEntry;
import site.pplee.jcode.codingagent.session.CustomEntry;
import site.pplee.jcode.codingagent.session.CustomMessageEntry;
import site.pplee.jcode.codingagent.session.LabelEntry;
import site.pplee.jcode.codingagent.session.ModelChangeEntry;
import site.pplee.jcode.codingagent.session.SessionEntry;
import site.pplee.jcode.codingagent.session.SessionInfoEntry;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;
import site.pplee.jcode.codingagent.session.SessionSnapshot;
import site.pplee.jcode.codingagent.session.ThinkingLevelChangeEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded display page from one immutable product history snapshot. */
public record HistoryDisplayPage(
        String headEntryId, String nextBeforeEntryId, List<Entry> entries
) {
    private static final int MAX_TEXT_CHARS = 8_192;

    public HistoryDisplayPage {
        entries = List.copyOf(Objects.requireNonNull(entries));
    }

    /** Select one fixed parent chain without moving the live session leaf. */
    public static HistoryDisplayPage from(
            SessionSnapshot snapshot, String headEntryId, String beforeEntryId, int limit
    ) {
        Objects.requireNonNull(snapshot);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("history page size must be between 1 and 100");
        }
        String head = headEntryId == null
                ? snapshot.currentEntryId().orElse(null) : headEntryId;
        if (head == null) {
            if (beforeEntryId != null) {
                throw new IllegalArgumentException("beforeEntryId requires a history head");
            }
            return new HistoryDisplayPage(null, null, List.of());
        }
        List<SessionEntry> branch = snapshot.branch(head);
        int from = branch.size() - 1;
        if (beforeEntryId != null) {
            from = -1;
            for (int index = 0; index < branch.size(); index++) {
                if (branch.get(index).id().equals(beforeEntryId)) {
                    from = index - 1;
                    break;
                }
            }
            if (from == -1 && !branch.getFirst().id().equals(beforeEntryId)) {
                throw new IllegalArgumentException("beforeEntryId is not on the selected branch");
            }
        }
        var items = new ArrayList<Entry>();
        for (int index = from; index >= 0 && items.size() < limit; index--) {
            items.add(display(branch.get(index)));
        }
        String next = from - items.size() >= 0 ? items.getLast().entryId() : null;
        return new HistoryDisplayPage(head, next, items);
    }

    private static Entry display(SessionEntry entry) {
        String role = null;
        String text;
        if (entry instanceof SessionMessageEntry messageEntry) {
            Message message = messageEntry.message().message();
            role = switch (message) {
                case Message.User ignored -> "user";
                case Message.Assistant ignored -> "assistant";
                case Message.ToolResultMessage ignored -> "tool";
            };
            List<Content> content = switch (message) {
                case Message.User user -> user.content();
                case Message.Assistant assistant -> assistant.content();
                case Message.ToolResultMessage tool -> tool.content();
            };
            var summary = contentText(content);
            return new Entry(entry.id(), entry.parentId(), entry.type(), role,
                    summary.text(), summary.truncated());
        }
        if (entry instanceof CustomMessageEntry customMessage) {
            var summary = contentText(customMessage.content());
            return new Entry(entry.id(), entry.parentId(), entry.type(), "custom",
                    summary.text(), summary.truncated());
        }
        text = switch (entry) {
            case CompactionEntry compact -> compact.summary();
            case BranchSummaryEntry summary -> summary.summary();
            case ModelChangeEntry model -> "model: " + model.model();
            case ThinkingLevelChangeEntry thinking -> "thinking level: " + thinking.thinkingLevel();
            case SessionInfoEntry info -> "session name: " + Objects.toString(info.name(), "(cleared)");
            case LabelEntry label -> "label for " + label.targetId();
            case CustomEntry custom -> "extension data: " + custom.extensionId()
                    + "/" + custom.customType();
            default -> entry.type();
        };
        var summary = bounded(text);
        return new Entry(entry.id(), entry.parentId(), entry.type(), role,
                summary.text(), summary.truncated());
    }

    private static Text contentText(List<Content> contents) {
        var text = new StringBuilder();
        boolean truncated = false;
        for (var content : contents) {
            if (!(content instanceof Content.Text)) {
                truncated = true;
            }
            String part = switch (content) {
                case Content.Text value -> value.text();
                case Content.Image image -> "[image: " + image.mediaType() + "]";
                case Content.ToolCall tool -> "[tool call: " + tool.name() + "]";
                case Content.Thinking ignored -> "[thinking]";
            };
            int remaining = MAX_TEXT_CHARS - text.length();
            if (text.length() > 0 && remaining > 0) {
                text.append('\n');
                remaining--;
            }
            if (part.length() > remaining) {
                text.append(part, 0, Math.max(0, remaining));
                truncated = true;
                break;
            }
            text.append(part);
        }
        return new Text(text.toString(), truncated);
    }

    private static Text bounded(String source) {
        return source.length() <= MAX_TEXT_CHARS
                ? new Text(source, false)
                : new Text(source.substring(0, MAX_TEXT_CHARS), true);
    }

    public record Entry(
            String entryId, String parentId, String type, String role,
            String text, boolean textTruncated
    ) { }

    private record Text(String text, boolean truncated) { }
}
