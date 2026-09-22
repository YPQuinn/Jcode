package site.pplee.jcode.codingagent.model;

import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Non-secret explanation of the current model and thinking selection. */
public record ModelSelection(
        Optional<ModelRef> requested,
        Optional<ModelRef> restored,
        ModelRef selected,
        ThinkingLevel thinkingLevel,
        Source source,
        List<ModelAssemblyDiagnostic> diagnostics
) {
    public ModelSelection {
        Objects.requireNonNull(requested, "requested must not be null");
        Objects.requireNonNull(restored, "restored must not be null");
        Objects.requireNonNull(selected, "selected must not be null");
        Objects.requireNonNull(thinkingLevel, "thinkingLevel must not be null");
        Objects.requireNonNull(source, "source must not be null");
        diagnostics = List.copyOf(
                Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }

    public enum Source {
        SDK,
        HISTORY,
        DEFAULT,
        RUNTIME
    }
}
