package site.pplee.jcode.codingagent.compaction;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.tool.ToolSpec;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import java.util.List;

/** Dependency-free heuristic estimator used consistently before and after compaction. */
public final class ContextUsageEstimator {
    public static final int IMAGE_TOKENS = 1_200;

    private ContextUsageEstimator() {
    }

    public static long estimate(
            String systemPrompt,
            List<ToolSpec> tools,
            List<? extends AgentMessage> messages
    ) {
        long characters = systemPrompt == null ? 0 : systemPrompt.length();
        for (var tool : tools) {
            characters += tool.name().length() + tool.description().length();
            characters += tool.parameters().toString().length();
            characters += tool.constraint().toString().length();
        }
        long images = 0;
        for (var agentMessage : messages) {
            if (!(agentMessage instanceof StandardAgentMessage standard)) {
                continue;
            }
            var counted = count(standard.message());
            characters += counted.characters();
            images += counted.images();
        }
        return divideByFour(characters) + images * IMAGE_TOKENS;
    }

    public static long estimateMessage(AgentMessage agentMessage) {
        if (!(agentMessage instanceof StandardAgentMessage standard)) {
            return 0;
        }
        var counted = count(standard.message());
        return divideByFour(counted.characters()) + counted.images() * IMAGE_TOKENS;
    }

    private static Count count(Message message) {
        long characters = 0;
        long images = 0;
        List<Content> contents;
        if (message instanceof Message.User user) {
            contents = user.content();
        } else if (message instanceof Message.Assistant assistant) {
            contents = assistant.content();
            if (assistant.errorMessage() != null) {
                characters += assistant.errorMessage().length();
            }
        } else {
            var result = (Message.ToolResultMessage) message;
            contents = result.content();
            characters += result.toolCallId().length() + result.toolName().length();
        }
        for (var content : contents) {
            switch (content) {
                case Content.Text text -> characters += text.text().length();
                case Content.Thinking thinking -> characters += thinking.text().length();
                case Content.ToolCall call -> characters += call.id().length()
                        + call.name().length() + call.arguments().toString().length();
                case Content.Image ignored -> images++;
            }
        }
        return new Count(characters, images);
    }

    private static long divideByFour(long characters) {
        return (characters + 3L) / 4L;
    }

    private record Count(long characters, long images) {
    }
}
