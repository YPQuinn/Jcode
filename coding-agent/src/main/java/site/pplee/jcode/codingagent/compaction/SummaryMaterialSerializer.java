package site.pplee.jcode.codingagent.compaction;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.codingagent.message.CustomAgentMessage;

import java.util.List;

/** Stable, bounded text representation for a no-tools summary request. */
public final class SummaryMaterialSerializer {
    private static final int TOOL_RESULT_LIMIT = 2_000;

    public String serialize(List<? extends AgentMessage> messages, String additionalInstructions) {
        var output = new StringBuilder();
        for (var agentMessage : messages) {
            if (!(agentMessage instanceof StandardAgentMessage standard)) {
                if (agentMessage instanceof CustomAgentMessage custom) {
                    output.append("\n[EXTENSION MESSAGE id=").append(custom.extensionId())
                            .append(" type=").append(custom.customType()).append("]\n");
                    appendContent(output, custom.content(), false);
                }
                continue;
            }
            appendMessage(output, standard.message());
        }
        if (additionalInstructions != null && !additionalInstructions.isBlank()) {
            output.append("\n[ADDITIONAL INSTRUCTIONS]\n")
                    .append(additionalInstructions.strip()).append('\n');
        }
        return output.toString();
    }

    private static void appendMessage(StringBuilder output, Message message) {
        if (message instanceof Message.User user) {
            output.append("\n[USER]\n");
            appendContent(output, user.content(), false);
        } else if (message instanceof Message.Assistant assistant) {
            output.append("\n[ASSISTANT stop=").append(assistant.stopReason()).append("]\n");
            appendContent(output, assistant.content(), false);
            if (assistant.errorMessage() != null) {
                output.append("error: ").append(assistant.errorMessage()).append('\n');
            }
        } else {
            var result = (Message.ToolResultMessage) message;
            output.append("\n[TOOL RESULT name=").append(result.toolName())
                    .append(" id=").append(result.toolCallId())
                    .append(" error=").append(result.error()).append("]\n");
            appendContent(output, result.content(), true);
        }
    }

    private static void appendContent(StringBuilder output, List<Content> contents, boolean truncateText) {
        for (var content : contents) {
            switch (content) {
                case Content.Text text -> output.append(truncateText
                        ? truncateCodePoints(text.text(), TOOL_RESULT_LIMIT) : text.text()).append('\n');
                case Content.Thinking thinking -> output.append("thinking: ")
                        .append(thinking.text()).append('\n');
                case Content.ToolCall call -> output.append("tool call: ")
                        .append(call.name()).append(' ').append(call.arguments()).append('\n');
                case Content.Image image -> output.append("[image ")
                        .append(image.mediaType()).append(" omitted]\n");
            }
        }
    }

    private static String truncateCodePoints(String value, int limit) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= limit) {
            return value;
        }
        int end = value.offsetByCodePoints(0, limit);
        return value.substring(0, end) + "\n[tool result truncated for summary input]";
    }
}
