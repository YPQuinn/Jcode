package site.pplee.jcode.codingagent.extension;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** One host-invoked command belonging to exactly one extension. */
public record ExtensionCommand(String name, Handler handler) {
    public ExtensionCommand {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(handler, "handler must not be null");
    }

    @FunctionalInterface
    public interface Handler {
        CompletionStage<CommandOutcome> execute(
                ExtensionContext context,
                List<String> arguments
        );
    }
}
