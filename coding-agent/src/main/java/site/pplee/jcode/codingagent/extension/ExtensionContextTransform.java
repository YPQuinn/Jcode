package site.pplee.jcode.codingagent.extension;

import site.pplee.jcode.agentcore.message.AgentMessage;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Request-local asynchronous transformation run before product message projection. */
@FunctionalInterface
public interface ExtensionContextTransform {
    CompletionStage<List<AgentMessage>> transform(
            ExtensionContext context,
            List<AgentMessage> messages
    );
}
