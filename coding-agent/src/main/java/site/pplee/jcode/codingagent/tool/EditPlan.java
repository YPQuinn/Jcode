package site.pplee.jcode.codingagent.tool;

import java.util.Objects;

/** Immutable result of planning all replacements against one original file. */
record EditPlan(byte[] finalBytes, int replacementCount, int firstChangedLine, boolean changed) {
    EditPlan {
        finalBytes = Objects.requireNonNull(finalBytes, "finalBytes must not be null").clone();
        if (replacementCount < 1) {
            throw new IllegalArgumentException("replacementCount must be positive");
        }
        if (firstChangedLine < 1) {
            throw new IllegalArgumentException("firstChangedLine must be positive");
        }
    }

    @Override
    public byte[] finalBytes() {
        return finalBytes.clone();
    }
}
