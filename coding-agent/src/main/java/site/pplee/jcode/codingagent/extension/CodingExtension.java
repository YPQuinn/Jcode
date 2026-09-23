package site.pplee.jcode.codingagent.extension;

import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.codingagent.event.CodingAgentEvent;
import site.pplee.jcode.codingagent.resource.ResourceSnapshot;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Explicit, borrowed Java extension with contributions fixed for one session lifetime. */
public interface CodingExtension {
    String id();

    default List<AgentTool<?>> tools() {
        return List.of();
    }

    default List<ExtensionCommand> commands() {
        return List.of();
    }

    default List<ExtensionContextTransform> contextTransforms() {
        return List.of();
    }

    default CompletionStage<Void> observe(CodingAgentEvent event) {
        return CompletableFuture.completedStage(null);
    }

    default CompletionStage<Void> onSessionStarted(ExtensionContext context) {
        return CompletableFuture.completedStage(null);
    }

    default CompletionStage<Void> onResourcesReloaded(
            ExtensionContext context,
            ResourceSnapshot previous
    ) {
        return CompletableFuture.completedStage(null);
    }

    default CompletionStage<Void> onSessionShutdown(ExtensionContext context) {
        return CompletableFuture.completedStage(null);
    }
}
