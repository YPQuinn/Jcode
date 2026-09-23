package site.pplee.jcode.codingagent.extension;

import java.util.List;
import java.util.Objects;

/** Handler output; display text is never implicitly submitted to a model. */
public record CommandOutcome(String displayText, List<CustomRecordDraft> records) {
    public CommandOutcome {
        records = List.copyOf(Objects.requireNonNull(records, "records must not be null"));
        if (records.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("records must not contain null");
        }
    }

    public static CommandOutcome display(String text) {
        return new CommandOutcome(text, List.of());
    }

    public static CommandOutcome records(List<CustomRecordDraft> records) {
        return new CommandOutcome(null, records);
    }
}
