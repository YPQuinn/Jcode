package site.pplee.jcode.codingagent.compaction;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.codingagent.session.SummaryDetails;

import java.util.Collection;
import java.util.TreeSet;

/** Extracts explicit path arguments from summary material without touching the filesystem. */
public final class SummaryDetailsExtractor {
    private SummaryDetailsExtractor() {
    }

    public static SummaryDetails extract(
            Collection<? extends AgentMessage> material,
            Collection<SummaryDetails> inherited
    ) {
        var read = new TreeSet<String>();
        var modified = new TreeSet<String>();
        for (var details : inherited) {
            read.addAll(details.readFiles());
            modified.addAll(details.modifiedFiles());
        }
        for (var agentMessage : material) {
            if (!(agentMessage instanceof StandardAgentMessage standard)
                    || !(standard.message() instanceof Message.Assistant assistant)) {
                continue;
            }
            assistant.content().stream()
                    .filter(Content.ToolCall.class::isInstance)
                    .map(Content.ToolCall.class::cast)
                    .forEach(call -> addPath(call, read, modified));
        }
        return new SummaryDetails(read.stream().toList(), modified.stream().toList());
    }

    private static void addPath(
            Content.ToolCall call,
            TreeSet<String> read,
            TreeSet<String> modified
    ) {
        var path = call.arguments().get("path");
        if (path == null || !path.isTextual() || path.textValue().isBlank()) {
            return;
        }
        switch (call.name()) {
            case "write", "edit" -> modified.add(path.textValue());
            case "read", "grep", "find", "ls" -> read.add(path.textValue());
            default -> {
                // Unknown tools do not imply file semantics.
            }
        }
    }
}
