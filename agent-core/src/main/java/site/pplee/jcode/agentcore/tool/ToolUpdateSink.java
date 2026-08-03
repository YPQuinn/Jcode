package site.pplee.jcode.agentcore.tool;

import site.pplee.jcode.ai.message.Content;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Channel for a tool to report incremental progress during execution. The
 * loop provides an implementation that emits {@code ToolUpdate} events. The
 * sink accepts updates until the execution stage settles; accepted updates
 * are fully delivered before the tool is considered complete, and updates
 * delivered after settlement are silently dropped.
 *
 * <p>A tool calls {@link #update} to deliver real-time content (e.g., partial
 * output, status text) to observers. The stage completes when the update has
 * been processed; a slow sink blocks the caller's thread.
 */
@FunctionalInterface
public interface ToolUpdateSink {
    /** Deliver an incremental update; the stage completes when processed. */
    CompletionStage<Void> update(Content update);

    /** Sink that discards all updates immediately. */
    static ToolUpdateSink noop() {
        return update -> CompletableFuture.completedStage(null);
    }
}
