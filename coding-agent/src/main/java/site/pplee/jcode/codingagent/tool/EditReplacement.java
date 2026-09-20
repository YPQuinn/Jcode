package site.pplee.jcode.codingagent.tool;

import java.util.Objects;

/** One exact replacement requested by the local {@code edit} tool. */
public record EditReplacement(String oldText, String newText) {
    public EditReplacement {
        Objects.requireNonNull(oldText, "oldText must not be null");
        Objects.requireNonNull(newText, "newText must not be null");
        if (oldText.isEmpty()) {
            throw new IllegalArgumentException("oldText must not be empty");
        }
    }
}
