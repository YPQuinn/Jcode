package site.pplee.jcode.codingagent.extension;

import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.codingagent.model.ModelSelection;
import site.pplee.jcode.codingagent.resource.ResourceSnapshot;
import site.pplee.jcode.codingagent.session.SessionSnapshot;

import java.nio.file.Path;
import java.util.Objects;

/** Read-only invocation context that excludes mutable runtime and writer objects. */
public record ExtensionContext(
        String extensionId,
        Path workingDirectory,
        ResourceSnapshot resources,
        SessionSnapshot history,
        ModelSelection modelSelection,
        CancellationSignal cancellation
) {
    public ExtensionContext {
        Objects.requireNonNull(extensionId, "extensionId must not be null");
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Objects.requireNonNull(resources, "resources must not be null");
        Objects.requireNonNull(history, "history must not be null");
        Objects.requireNonNull(modelSelection, "modelSelection must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
    }
}
