package site.pplee.jcode.codingagent.compaction;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.codingagent.session.BranchSummaryEntry;
import site.pplee.jcode.codingagent.session.CompactionEntry;
import site.pplee.jcode.codingagent.session.SessionEntry;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;
import site.pplee.jcode.codingagent.session.SessionSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** Pure selection of an older prefix and a context-visible retained boundary. */
public final class CompactionPlanner {
    public Optional<Plan> plan(SessionSnapshot snapshot, int keepRecentTokens) {
        var branch = snapshot.currentBranch();
        var units = effectiveUnits(branch);
        if (units.size() < 2) {
            return Optional.empty();
        }

        long retained = 0;
        int keepIndex = units.size() - 1;
        for (int index = units.size() - 1; index >= 0; index--) {
            long next = ContextUsageEstimator.estimateMessage(units.get(index).message());
            if (index < units.size() - 1 && retained + next > keepRecentTokens) {
                break;
            }
            retained += next;
            keepIndex = index;
        }
        int latestUser = latestUserIndex(units);
        boolean splitTurn = latestUser >= 0 && hasToolActivityAfter(units, latestUser);
        if (latestUser >= 0 && !splitTurn) {
            keepIndex = Math.min(keepIndex, latestUser);
        }
        keepIndex = toolGroupStart(units, keepIndex);
        while (keepIndex < units.size() && units.get(keepIndex).entry() == null) {
            keepIndex++;
        }
        if (keepIndex <= 0 || keepIndex >= units.size()) {
            return Optional.empty();
        }
        var firstKept = units.get(keepIndex).entry();
        if (!(firstKept instanceof SessionMessageEntry || firstKept instanceof BranchSummaryEntry)) {
            return Optional.empty();
        }
        var material = units.subList(0, keepIndex).stream().map(Unit::message).toList();
        if (material.isEmpty()) {
            return Optional.empty();
        }
        var retainedMessages = units.subList(keepIndex, units.size()).stream().map(Unit::message).toList();
        return Optional.of(new Plan(firstKept.id(), material, retainedMessages, splitTurn));
    }

    private static List<Unit> effectiveUnits(List<SessionEntry> branch) {
        int checkpointIndex = -1;
        for (int index = 0; index < branch.size(); index++) {
            if (branch.get(index) instanceof CompactionEntry) {
                checkpointIndex = index;
            }
        }
        if (checkpointIndex >= 0 && !hasVisibleContentAfter(branch, checkpointIndex)) {
            return List.of();
        }
        var units = new ArrayList<Unit>();
        int start = 0;
        if (checkpointIndex >= 0) {
            var checkpoint = (CompactionEntry) branch.get(checkpointIndex);
            units.add(new Unit(null, synthetic("previous-compaction", checkpoint.summary(), checkpoint.timestamp())));
            start = indexOf(branch, checkpoint.firstKeptEntryId());
            append(branch, start, checkpointIndex, units);
            append(branch, checkpointIndex + 1, branch.size(), units);
        } else {
            append(branch, 0, branch.size(), units);
        }
        return List.copyOf(units);
    }

    private static boolean hasVisibleContentAfter(List<SessionEntry> branch, int checkpointIndex) {
        for (int index = checkpointIndex + 1; index < branch.size(); index++) {
            if (branch.get(index) instanceof SessionMessageEntry
                    || branch.get(index) instanceof BranchSummaryEntry) {
                return true;
            }
        }
        return false;
    }

    private static void append(List<SessionEntry> branch, int from, int to, List<Unit> units) {
        for (int index = from; index < to; index++) {
            var entry = branch.get(index);
            if (entry instanceof SessionMessageEntry message) {
                units.add(new Unit(entry, message.message()));
            } else if (entry instanceof BranchSummaryEntry summary) {
                units.add(new Unit(entry, synthetic("branch-summary", summary.summary(), summary.timestamp())));
            }
        }
    }

    private static int toolGroupStart(List<Unit> units, int requested) {
        if (!isToolResult(units.get(requested).message())) {
            return requested;
        }
        var resultIds = new HashSet<String>();
        for (int index = requested; index < units.size(); index++) {
            var message = standard(units.get(index).message());
            if (message instanceof Message.ToolResultMessage result) {
                resultIds.add(result.toolCallId());
            } else {
                break;
            }
        }
        for (int index = requested - 1; index >= 0; index--) {
            var message = standard(units.get(index).message());
            if (message instanceof Message.Assistant assistant) {
                var callIds = assistant.content().stream()
                        .filter(Content.ToolCall.class::isInstance)
                        .map(Content.ToolCall.class::cast)
                        .map(Content.ToolCall::id)
                        .collect(java.util.stream.Collectors.toSet());
                if (callIds.containsAll(resultIds)) {
                    return index;
                }
            }
        }
        return requested;
    }

    private static boolean isToolResult(AgentMessage message) {
        return standard(message) instanceof Message.ToolResultMessage;
    }

    private static int latestUserIndex(List<Unit> units) {
        for (int index = units.size() - 1; index >= 0; index--) {
            if (standard(units.get(index).message()) instanceof Message.User) {
                return index;
            }
        }
        return -1;
    }

    private static boolean hasToolActivityAfter(List<Unit> units, int userIndex) {
        for (int index = userIndex + 1; index < units.size(); index++) {
            var message = standard(units.get(index).message());
            if (message instanceof Message.ToolResultMessage) {
                return true;
            }
            if (message instanceof Message.Assistant assistant
                    && assistant.content().stream().anyMatch(Content.ToolCall.class::isInstance)) {
                return true;
            }
        }
        return false;
    }

    private static Message standard(AgentMessage message) {
        return ((StandardAgentMessage) message).message();
    }

    private static int indexOf(List<SessionEntry> branch, String id) {
        for (int index = 0; index < branch.size(); index++) {
            if (branch.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("entry is not on current branch: " + id);
    }

    private static StandardAgentMessage synthetic(String kind, String text, java.time.Instant timestamp) {
        return StandardAgentMessage.of(new Message.User(
                List.of(new Content.Text("[" + kind + "]\n" + text)), timestamp));
    }

    public record Plan(
            String firstKeptEntryId,
            List<AgentMessage> material,
            List<AgentMessage> retainedMessages,
            boolean splitTurn
    ) {
        public Plan {
            material = List.copyOf(material);
            retainedMessages = List.copyOf(retainedMessages);
        }
    }

    private record Unit(SessionEntry entry, AgentMessage message) {
    }
}
