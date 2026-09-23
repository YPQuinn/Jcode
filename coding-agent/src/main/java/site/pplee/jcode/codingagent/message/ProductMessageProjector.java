package site.pplee.jcode.codingagent.message;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.MessageProjector;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import java.util.ArrayList;
import java.util.List;

/** Explicit projection for every product message type supported by persisted history. */
public final class ProductMessageProjector {
    private ProductMessageProjector() {
    }

    public static MessageProjector create() {
        return ProductMessageProjector::project;
    }

    private static List<Message> project(List<AgentMessage> messages) {
        var projected = new ArrayList<Message>(messages.size());
        for (var message : messages) {
            if (message instanceof StandardAgentMessage standard) {
                projected.add(standard.message());
            } else if (message instanceof CustomAgentMessage custom) {
                projected.add(projectCustom(custom));
            } else {
                throw new IllegalArgumentException(
                        "unsupported product message type: " + message.getClass().getName());
            }
        }
        return List.copyOf(projected);
    }

    private static Message.User projectCustom(CustomAgentMessage message) {
        var contents = new ArrayList<Content>();
        String open = "<extension_message extension_id=\"" + attribute(message.extensionId())
                + "\" custom_type=\"" + attribute(message.customType()) + "\">\n";
        contents.add(new Content.Text(open));
        contents.addAll(message.content());
        contents.add(new Content.Text("\n</extension_message>"));
        return new Message.User(contents, message.timestamp());
    }

    private static String attribute(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
