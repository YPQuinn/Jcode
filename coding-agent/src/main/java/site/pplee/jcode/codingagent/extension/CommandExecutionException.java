package site.pplee.jcode.codingagent.extension;

import java.util.List;

/** Command failure that preserves the append-only prefix accepted before the failure. */
public final class CommandExecutionException extends RuntimeException {
    private final List<String> acceptedEntryIds;

    public CommandExecutionException(String message, Throwable cause, List<String> acceptedEntryIds) {
        super(message, cause);
        this.acceptedEntryIds = List.copyOf(acceptedEntryIds);
    }

    public List<String> acceptedEntryIds() {
        return acceptedEntryIds;
    }
}
