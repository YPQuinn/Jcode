package site.pplee.jcode.codingagent.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionContextBuilderCompactionTest {
    private static final Instant TIME = Instant.parse("2026-09-22T00:00:00Z");
    private static final ModelRef MODEL = new ModelRef("test", "test", "model");

    @TempDir
    Path directory;

    @Test
    void latestCheckpointProjectsSummaryRetainedBoundaryAndLaterMessagesOnly() {
        var entries = List.<SessionEntry>of(
                message("u0", null, user("u0")),
                message("a0", "u0", assistant("a0")),
                message("u1", "a0", user("u1")),
                message("a1", "u1", assistant("a1")),
                new CompactionEntry(
                        "c1", "a1", TIME, "summary one", "u1", 100,
                        TokenEstimateSource.FULL_ESTIMATE, MODEL, Usage.zero(), SummaryDetails.empty()),
                message("u2", "c1", user("u2")),
                message("a2", "u2", assistant("a2")),
                new CompactionEntry(
                        "c2", "a2", TIME, "summary two", "u2", 80,
                        TokenEstimateSource.FULL_ESTIMATE, MODEL, Usage.zero(), SummaryDetails.empty()));
        var snapshot = new SessionSnapshot(
                new SessionHeader(UUID.randomUUID(), TIME, directory),
                entries, "c2", null, Map.of());

        assertEquals(List.of("summary two", "u2", "a2"),
                texts(SessionContextBuilder.buildRequestView(snapshot)));
        assertEquals(List.of("u0", "a0", "u1", "a1", "u2", "a2"),
                texts(SessionContextBuilder.build(snapshot)));
        assertEquals(List.of("u0", "a0"),
                texts(SessionContextBuilder.buildRequestView(snapshot, "a0")));
    }

    private static SessionMessageEntry message(String id, String parent, Message message) {
        return new SessionMessageEntry(id, parent, TIME, StandardAgentMessage.of(message));
    }

    private static Message.User user(String text) {
        return new Message.User(List.of(new Content.Text(text)), TIME);
    }

    private static Message.Assistant assistant(String text) {
        return new Message.Assistant(
                List.of(new Content.Text(text)), StopReason.STOP, null,
                Usage.zero(), TIME, MODEL);
    }

    private static List<String> texts(SessionContext context) {
        return context.messages().stream()
                .map(StandardAgentMessage.class::cast)
                .map(StandardAgentMessage::message)
                .map(message -> {
                    Content.Text text = (Content.Text) switch (message) {
                        case Message.User user -> user.content().getFirst();
                        case Message.Assistant assistant -> assistant.content().getFirst();
                        case Message.ToolResultMessage result -> result.content().getFirst();
                    };
                    String value = text.text();
                    if (value.contains("summary two")) {
                        return "summary two";
                    }
                    return value;
                })
                .toList();
    }
}
