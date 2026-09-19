package site.pplee.jcode.codingagent.tool;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Product-level authorization hook evaluated before a built-in tool executes.
 *
 * <p>A policy can be called concurrently for a read-only tool batch and must
 * therefore be thread-safe. It receives only defensive product snapshots and
 * must not assume that approval creates a filesystem or process sandbox.
 */
@FunctionalInterface
public interface CodingToolPolicy {
    /** Evaluate one prepared tool request. */
    CompletionStage<Decision> evaluate(
            CodingToolRequest request,
            CancellationSignal cancellation
    );

    /** Return a policy that allows every enabled tool request. */
    static CodingToolPolicy allowAll() {
        return (request, cancellation) ->
                CompletableFuture.completedStage(new Decision.Allow());
    }

    /** Authorization result returned by a policy. */
    sealed interface Decision permits Decision.Allow, Decision.Deny {
        /** Allow execution to continue. */
        record Allow() implements Decision {
        }

        /** Refuse execution with a model-visible reason. */
        record Deny(String reason) implements Decision {
            public Deny {
                Objects.requireNonNull(reason, "reason must not be null");
                if (reason.isBlank()) {
                    throw new IllegalArgumentException("reason must not be blank");
                }
            }
        }
    }
}
