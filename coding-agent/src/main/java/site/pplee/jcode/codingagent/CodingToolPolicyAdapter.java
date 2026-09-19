package site.pplee.jcode.codingagent;

import site.pplee.jcode.agentcore.tool.BeforeToolCall;
import site.pplee.jcode.codingagent.tool.CodingToolPolicy;
import site.pplee.jcode.codingagent.tool.CodingToolRequest;

import java.nio.file.Path;

/** Adapts the product authorization contract to the runtime prepare hook. */
final class CodingToolPolicyAdapter {
    private CodingToolPolicyAdapter() {
    }

    /** Create a before hook without exposing runtime tool or context objects. */
    static BeforeToolCall adapt(CodingToolPolicy policy, Path workingDirectory) {
        return (call, tool, preparedArguments, context, cancellation) -> {
            var request = new CodingToolRequest(
                    call.id(), call.name(), preparedArguments, workingDirectory);
            var stage = policy.evaluate(request, cancellation);
            if (stage == null) {
                return null;
            }
            return stage.thenApply(decision -> {
                if (decision == null) {
                    return null;
                }
                if (decision instanceof CodingToolPolicy.Decision.Allow) {
                    return new BeforeToolCall.Decision.Proceed();
                }
                var denial = (CodingToolPolicy.Decision.Deny) decision;
                return new BeforeToolCall.Decision.Block(denial.reason());
            });
        };
    }
}
