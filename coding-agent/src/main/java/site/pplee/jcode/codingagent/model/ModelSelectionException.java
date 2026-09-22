package site.pplee.jcode.codingagent.model;

import java.util.List;
import java.util.Objects;

/** Checked assembly failure carrying only non-secret selection diagnostics. */
public final class ModelSelectionException extends Exception {
    private final List<ModelAssemblyDiagnostic> diagnostics;

    public ModelSelectionException(String message, List<ModelAssemblyDiagnostic> diagnostics) {
        super(message);
        this.diagnostics = List.copyOf(
                Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }

    public List<ModelAssemblyDiagnostic> diagnostics() {
        return diagnostics;
    }
}
