package site.pplee.jcode.agentcore.support;

import site.pplee.jcode.agentcore.concurrent.CancellationToken;
import site.pplee.jcode.agentcore.model.Content;
import site.pplee.jcode.agentcore.model.ToolExecutionMode;
import site.pplee.jcode.agentcore.model.ToolResult;
import site.pplee.jcode.agentcore.spi.AgentTool;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

/**
 * Test-only {@link AgentTool} implementations for {@code AgentLoopTest}.
 */
public final class TestTools {
    private TestTools() {}

    /** Returns the received text argument as text content. */
    public static AgentTool<String> echo() {
        return new AgentTool<String>() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public Class<String> argumentType() {
                return String.class;
            }

            @Override
            public CompletionStage<ToolResult> execute(
                    String toolCallId, String arguments, CancellationToken cancellation) {
                return CompletableFuture.completedFuture(
                        ToolResult.success(List.of(new Content.Text(arguments))));
            }
        };
    }

    /** Returns a failed stage to exercise the loop's tool-failure path. */
    public static AgentTool<Object> failing() {
        return new AgentTool<Object>() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public Class<Object> argumentType() {
                return Object.class;
            }

            @Override
            public CompletionStage<ToolResult> execute(
                    String toolCallId, Object arguments, CancellationToken cancellation) {
                return CompletableFuture.failedFuture(new RuntimeException("boom"));
            }
        };
    }

    /** Returns a successful result with terminate=true. */
    public static AgentTool<Object> terminating() {
        return new AgentTool<Object>() {
            @Override
            public String name() {
                return "terminating";
            }

            @Override
            public Class<Object> argumentType() {
                return Object.class;
            }

            @Override
            public CompletionStage<ToolResult> execute(
                    String toolCallId, Object arguments, CancellationToken cancellation) {
                return CompletableFuture.completedFuture(
                        ToolResult.success(List.of(new Content.Text("done")), true));
            }
        };
    }

    /**
     * A tool that counts down {@code started} on entry, then blocks on
     * {@code release} before returning. With a shared {@code started} latch
     * (count = number of tools) it verifies concurrent (parallel) execution;
     * with separate per-tool latches it verifies non-overlapping (sequential)
     * execution. Never uses sleep.
     */
    public static AgentTool<Object> blocking(
            String name, CountDownLatch started, CountDownLatch release, ToolExecutionMode mode) {
        return new AgentTool<Object>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Class<Object> argumentType() {
                return Object.class;
            }

            @Override
            public ToolExecutionMode executionMode() {
                return mode;
            }

            @Override
            public CompletionStage<ToolResult> execute(
                    String toolCallId, Object arguments, CancellationToken cancellation) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(e);
                }
                return CompletableFuture.completedFuture(
                        ToolResult.success(List.of(new Content.Text("ok"))));
            }
        };
    }
}
