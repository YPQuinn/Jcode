package site.pplee.jcode.codingagent.extension;

import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.codingagent.event.CodingAgentEvent;
import site.pplee.jcode.codingagent.internal.SnapshotMapper;
import site.pplee.jcode.codingagent.message.CustomAgentMessage;
import site.pplee.jcode.codingagent.resource.ResourceSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Fixed contribution registry and ordered callback runner for borrowed extensions. */
public final class ExtensionRunner {
    private final List<Registered> extensions;
    private final List<AgentTool<?>> tools;
    private final Map<CommandKey, RegisteredCommand> commands;

    public ExtensionRunner(List<? extends CodingExtension> configured) {
        Objects.requireNonNull(configured, "configured must not be null");
        var registered = new ArrayList<Registered>();
        var collectedTools = new ArrayList<AgentTool<?>>();
        var commandMap = new LinkedHashMap<CommandKey, RegisteredCommand>();
        var extensionIds = new java.util.HashSet<String>();
        var toolNames = new java.util.HashSet<String>();
        for (var extension : configured) {
            Objects.requireNonNull(extension, "configured must not contain null");
            String id = requireName(extension.id(), "extension id");
            if (!extensionIds.add(id)) {
                throw new IllegalArgumentException("duplicate extension id: " + id);
            }
            var extensionTools = copyNoNulls(extension.tools(), "extension tools");
            var extensionCommands = copyNoNulls(extension.commands(), "extension commands");
            var transforms = copyNoNulls(extension.contextTransforms(), "extension context transforms");
            for (var tool : extensionTools) {
                String name = tool.spec().name();
                if (!toolNames.add(name)) {
                    throw new IllegalArgumentException("duplicate extension tool name: " + name);
                }
                collectedTools.add(tool);
            }
            var names = new java.util.HashSet<String>();
            for (var command : extensionCommands) {
                if (!names.add(command.name())) {
                    throw new IllegalArgumentException(
                            "duplicate command for extension " + id + ": " + command.name());
                }
                var key = new CommandKey(id, command.name());
                commandMap.put(key, new RegisteredCommand(id, command));
            }
            registered.add(new Registered(id, extension, transforms));
        }
        extensions = List.copyOf(registered);
        tools = List.copyOf(collectedTools);
        commands = Map.copyOf(commandMap);
    }

    public List<AgentTool<?>> tools() {
        return tools;
    }

    public ExtensionCommand command(String extensionId, String commandName) {
        var registered = commands.get(new CommandKey(extensionId, commandName));
        if (registered == null) {
            throw new IllegalArgumentException(
                    "unknown extension command: " + extensionId + "/" + commandName);
        }
        return registered.command();
    }

    public boolean hasContextTransforms() {
        return extensions.stream().anyMatch(extension -> !extension.transforms().isEmpty());
    }

    public CompletionStage<List<AgentMessage>> transform(
            List<AgentMessage> initial,
            Function<String, ExtensionContext> contexts,
            Function<CodingAgentEvent.ExtensionDiagnostic, CompletionStage<Void>> diagnostics
    ) {
        CompletionStage<List<AgentMessage>> stage = CompletableFuture.completedStage(
                SnapshotMapper.agentMessages(initial));
        for (var extension : extensions) {
            for (var transform : extension.transforms()) {
                stage = stage.thenCompose(previous -> {
                    var context = contexts.apply(extension.id());
                    context.cancellation().throwIfCancelled();
                    return runTransform(
                            extension.id(), transform, previous, context, diagnostics);
                });
            }
        }
        return stage;
    }

    public CompletionStage<Void> observe(
            CodingAgentEvent event,
            Function<CodingAgentEvent.ExtensionDiagnostic, CompletionStage<Void>> diagnostics
    ) {
        CompletionStage<Void> stage = CompletableFuture.completedStage(null);
        for (var extension : extensions) {
            stage = stage.thenCompose(ignored -> recoverVoid(
                    extension.id(), "observer", () -> extension.extension().observe(event), diagnostics));
        }
        return stage;
    }

    public CompletionStage<Void> started(
            Function<String, ExtensionContext> contexts,
            Function<CodingAgentEvent.ExtensionDiagnostic, CompletionStage<Void>> diagnostics
    ) {
        return lifecycle("session-started", contexts, diagnostics,
                CodingExtension::onSessionStarted);
    }

    public CompletionStage<Void> resourcesReloaded(
            ResourceSnapshot previous,
            Function<String, ExtensionContext> contexts,
            Function<CodingAgentEvent.ExtensionDiagnostic, CompletionStage<Void>> diagnostics
    ) {
        return lifecycle("resources-reloaded", contexts, diagnostics,
                (extension, context) -> extension.onResourcesReloaded(context, previous));
    }

    public CompletionStage<Void> shutdown(
            Function<String, ExtensionContext> contexts,
            Function<CodingAgentEvent.ExtensionDiagnostic, CompletionStage<Void>> diagnostics
    ) {
        var reversed = new ArrayList<>(extensions);
        java.util.Collections.reverse(reversed);
        CompletionStage<Throwable> stage = CompletableFuture.completedStage(null);
        for (var registered : reversed) {
            stage = stage.thenCompose(previous -> runShutdown(
                    registered, contexts, diagnostics)
                    .thenApply(current -> combineFailures(previous, current)));
        }
        return stage.thenCompose(failure -> failure == null
                ? CompletableFuture.completedStage(null)
                : CompletableFuture.failedStage(failure));
    }

    private CompletionStage<Void> lifecycle(
            String phase,
            Function<String, ExtensionContext> contexts,
            Function<CodingAgentEvent.ExtensionDiagnostic, CompletionStage<Void>> diagnostics,
            Lifecycle callback
    ) {
        CompletionStage<Void> stage = CompletableFuture.completedStage(null);
        for (var registered : extensions) {
            stage = stage.thenCompose(ignored -> recoverVoid(
                    registered.id(), phase,
                    () -> callback.invoke(
                            registered.extension(), contexts.apply(registered.id())), diagnostics));
        }
        return stage;
    }

    private CompletionStage<List<AgentMessage>> runTransform(
            String extensionId,
            ExtensionContextTransform transform,
            List<AgentMessage> previous,
            ExtensionContext context,
            Function<CodingAgentEvent.ExtensionDiagnostic, CompletionStage<Void>> diagnostics
    ) {
        CompletionStage<List<AgentMessage>> invoked;
        try {
            invoked = Objects.requireNonNull(
                    transform.transform(
                            context, SnapshotMapper.agentMessages(previous)),
                    "context transform returned null stage");
        } catch (Throwable failure) {
            return recoverTransform(extensionId, previous, failure, context, diagnostics);
        }
        return invoked.handle((value, failure) -> new TransformOutcome(value, failure))
                .thenCompose(outcome -> {
                    if (outcome.failure() != null) {
                        return recoverTransform(
                                extensionId, previous, outcome.failure(), context, diagnostics);
                    }
                    try {
                        context.cancellation().throwIfCancelled();
                        return CompletableFuture.completedStage(
                                validateMessages(outcome.messages()));
                    } catch (Throwable failure) {
                        return recoverTransform(
                                extensionId, previous, failure, context, diagnostics);
                    }
                });
    }

    private CompletionStage<List<AgentMessage>> recoverTransform(
            String extensionId,
            List<AgentMessage> previous,
            Throwable failure,
            ExtensionContext context,
            Function<CodingAgentEvent.ExtensionDiagnostic, CompletionStage<Void>> diagnostics
    ) {
        Throwable actual = unwrap(failure);
        if (actual instanceof Error || actual instanceof CancellationException) {
            return CompletableFuture.failedStage(actual);
        }
        try {
            context.cancellation().throwIfCancelled();
        } catch (CancellationException cancelled) {
            return CompletableFuture.failedStage(cancelled);
        }
        return requireStage(diagnostics.apply(diagnostic(extensionId, "context-transform", actual)))
                .thenApply(ignored -> {
                    context.cancellation().throwIfCancelled();
                    return SnapshotMapper.agentMessages(previous);
                });
    }

    private CompletionStage<Throwable> runShutdown(
            Registered registered,
            Function<String, ExtensionContext> contexts,
            Function<CodingAgentEvent.ExtensionDiagnostic, CompletionStage<Void>> diagnostics
    ) {
        CompletionStage<Void> invoked;
        try {
            invoked = requireStage(registered.extension().onSessionShutdown(
                    contexts.apply(registered.id())));
        } catch (Throwable failure) {
            return reportShutdownFailure(registered.id(), failure, diagnostics);
        }
        return invoked.handle((ignored, failure) -> failure)
                .thenCompose(failure -> failure == null
                        ? CompletableFuture.completedStage(null)
                        : reportShutdownFailure(registered.id(), failure, diagnostics));
    }

    private CompletionStage<Throwable> reportShutdownFailure(
            String extensionId,
            Throwable failure,
            Function<CodingAgentEvent.ExtensionDiagnostic, CompletionStage<Void>> diagnostics
    ) {
        Throwable actual = unwrap(failure);
        if (actual instanceof Error || actual instanceof CancellationException) {
            return CompletableFuture.completedStage(actual);
        }
        CompletionStage<Void> reported;
        try {
            reported = requireStage(diagnostics.apply(
                    diagnostic(extensionId, "session-shutdown", actual)));
        } catch (Throwable diagnosticFailure) {
            addSuppressed(actual, unwrap(diagnosticFailure));
            return CompletableFuture.completedStage(actual);
        }
        return reported.handle((ignored, diagnosticFailure) -> {
            if (diagnosticFailure != null) {
                addSuppressed(actual, unwrap(diagnosticFailure));
            }
            return actual;
        });
    }

    private static Throwable combineFailures(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (next != null) {
            addSuppressed(first, next);
        }
        return first;
    }

    private static void addSuppressed(Throwable target, Throwable suppressed) {
        if (target != suppressed) {
            target.addSuppressed(suppressed);
        }
    }

    private CompletionStage<Void> recoverVoid(
            String extensionId,
            String phase,
            StageSupplier callback,
            Function<CodingAgentEvent.ExtensionDiagnostic, CompletionStage<Void>> diagnostics
    ) {
        CompletionStage<Void> invoked;
        try {
            invoked = requireStage(callback.get());
        } catch (Throwable failure) {
            return recoverVoidFailure(extensionId, phase, failure, diagnostics);
        }
        return invoked.handle((ignored, failure) -> failure)
                .thenCompose(failure -> failure == null
                        ? CompletableFuture.completedStage(null)
                        : recoverVoidFailure(extensionId, phase, failure, diagnostics));
    }

    private CompletionStage<Void> recoverVoidFailure(
            String extensionId,
            String phase,
            Throwable failure,
            Function<CodingAgentEvent.ExtensionDiagnostic, CompletionStage<Void>> diagnostics
    ) {
        Throwable actual = unwrap(failure);
        if (actual instanceof Error || actual instanceof CancellationException) {
            return CompletableFuture.failedStage(actual);
        }
        return requireStage(diagnostics.apply(diagnostic(extensionId, phase, actual)));
    }

    private static List<AgentMessage> validateMessages(List<AgentMessage> messages) {
        var copy = SnapshotMapper.agentMessages(
                Objects.requireNonNull(messages, "context transform returned null messages"));
        for (var message : copy) {
            if (!(message instanceof site.pplee.jcode.agentcore.message.StandardAgentMessage
                    || message instanceof CustomAgentMessage)) {
                throw new IllegalArgumentException(
                        "unsupported context-transform message: " + message.getClass().getName());
            }
        }
        return copy;
    }

    private static CodingAgentEvent.ExtensionDiagnostic diagnostic(
            String extensionId,
            String phase,
            Throwable failure
    ) {
        return new CodingAgentEvent.ExtensionDiagnostic(
                extensionId, phase, failure.getClass().getSimpleName());
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> CompletionStage<T> requireStage(CompletionStage<T> stage) {
        return Objects.requireNonNull(stage, "extension callback returned null stage");
    }

    private static <T> List<T> copyNoNulls(List<T> values, String field) {
        var result = List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
        if (result.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(field + " must not contain null");
        }
        return result;
    }

    private static String requireName(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private record Registered(
            String id,
            CodingExtension extension,
            List<ExtensionContextTransform> transforms
    ) {
    }

    private record CommandKey(String extensionId, String commandName) {
    }

    private record RegisteredCommand(String extensionId, ExtensionCommand command) {
    }

    private record TransformOutcome(List<AgentMessage> messages, Throwable failure) {
    }

    @FunctionalInterface
    private interface StageSupplier {
        CompletionStage<Void> get();
    }

    @FunctionalInterface
    private interface Lifecycle {
        CompletionStage<Void> invoke(CodingExtension extension, ExtensionContext context);
    }
}
