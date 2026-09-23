package site.pplee.jcode.codingagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.codingagent.compaction.CompactionPlanner;
import site.pplee.jcode.codingagent.compaction.ContextUsageEstimator;
import site.pplee.jcode.codingagent.session.CustomEntry;
import site.pplee.jcode.codingagent.session.CustomMessageEntry;
import site.pplee.jcode.codingagent.session.SessionHeader;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;
import site.pplee.jcode.codingagent.session.SessionSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CustomSessionCodecTest {
    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @Test
    void customStateAndVisibleMessageRoundTripWithoutJavaTypeMetadata() throws Exception {
        var mapper = new ObjectMapper();
        var codec = new SessionCodec();
        var state = new CustomEntry(
                "state", null, NOW, "review", "progress",
                mapper.createObjectNode().set("precise",
                        com.fasterxml.jackson.databind.node.DecimalNode.valueOf(
                                new BigDecimal("1234567890.1234567890123456789"))));
        var decodedState = assertInstanceOf(
                CustomEntry.class, codec.decodeEntry(codec.encodeEntry(state)));
        assertEquals(new BigDecimal("1234567890.1234567890123456789"),
                decodedState.data().get("precise").decimalValue());

        var message = new CustomMessageEntry(
                "message", "state", NOW, "review", "note",
                List.of(new Content.Text("visible"), new Content.Image("image/png", "AA==")),
                mapper.createObjectNode().put("private", true), false);
        byte[] encoded = codec.encodeEntry(message);
        String json = new String(encoded, java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(json.contains("@class"));
        assertFalse(json.contains("CustomMessageEntry"));
        var decoded = assertInstanceOf(
                CustomMessageEntry.class, codec.decodeEntry(encoded));
        assertEquals("review", decoded.extensionId());
        assertEquals("visible", assertInstanceOf(
                Content.Text.class, decoded.content().getFirst()).text());
        assertTrue(decoded.details().get("private").booleanValue());
        assertFalse(decoded.display());
    }

    @Test
    void customMessageCanBeTheRetainedCompactionBoundary() {
        var model = new ModelRef("test", "test", "model");
        var user = new SessionMessageEntry(
                "user", null, NOW,
                StandardAgentMessage.of(new Message.User(
                        List.of(new Content.Text("old user ".repeat(100))), NOW)));
        var assistant = new SessionMessageEntry(
                "assistant", "user", NOW,
                StandardAgentMessage.of(new Message.Assistant(
                        List.of(new Content.Text("old answer ".repeat(100))),
                        StopReason.STOP, null, Usage.zero(), NOW, model)));
        var state = new CustomEntry(
                "state", "assistant", NOW, "review", "state",
                new ObjectMapper().createObjectNode().put("private", "hidden"));
        var custom = new CustomMessageEntry(
                "custom", "state", NOW, "review", "note",
                List.of(new Content.Text("new task")),
                new ObjectMapper().createObjectNode().put("private", "details"), true);
        var snapshot = new SessionSnapshot(
                new SessionHeader(UUID.randomUUID(), NOW, Path.of(".").toAbsolutePath()),
                List.of(user, assistant, state, custom), "custom", null, Map.of());
        int keep = Math.toIntExact(ContextUsageEstimator.estimateMessage(custom.message()));

        var plan = new CompactionPlanner().plan(snapshot, keep).orElseThrow();

        assertEquals("custom", plan.firstKeptEntryId());
        assertEquals(List.of(user.message(), assistant.message()), plan.material());
        assertEquals(List.of(custom.message()), plan.retainedMessages());
    }
}
