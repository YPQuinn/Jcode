package site.pplee.jcode.codingagent.extension;

import java.util.List;
import java.util.Objects;

/** Completed command output plus the exact history entries accepted in order. */
public record CommandResult(String displayText, List<String> acceptedEntryIds) {
    public CommandResult {
        acceptedEntryIds = List.copyOf(Objects.requireNonNull(
                acceptedEntryIds, "acceptedEntryIds must not be null"));
    }
}
