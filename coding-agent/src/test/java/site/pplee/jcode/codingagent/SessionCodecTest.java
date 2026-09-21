package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.session.LabelEntry;
import site.pplee.jcode.codingagent.session.ModelChangeEntry;
import site.pplee.jcode.codingagent.session.SessionEntry;
import site.pplee.jcode.codingagent.session.SessionHeader;
import site.pplee.jcode.codingagent.session.SessionInfoEntry;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;
import site.pplee.jcode.codingagent.session.ThinkingLevelChangeEntry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.CostEstimate;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.ModelReplayState;
import site.pplee.jcode.ai.message.ResponseMetadata;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCodecTest {
    private static final Instant T1 = Instant.parse("2026-09-20T00:00:00.123456Z");
    private static final ModelRef MODEL = new ModelRef("provider", "responses", "model-1");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec();

    @TempDir
    Path tempDir;

    @Test
    void headerAndAllFiveEntryTypesRoundTrip() throws Exception {
        var header = new SessionHeader(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), T1, tempDir);
        assertEquals(header, codec.decodeHeader(codec.encodeHeader(header)));

        var entries = List.<SessionEntry>of(
                new SessionMessageEntry("message", null, T1,
                        StandardAgentMessage.of(new Message.User(
                                List.of(new Content.Text("hello")), T1))),
                new ModelChangeEntry("model", "message", T1, MODEL),
                new ThinkingLevelChangeEntry("thinking", "model", T1, ThinkingLevel.XHIGH),
                new SessionInfoEntry("info", "thinking", T1, null),
                new LabelEntry("label", "info", T1, "message", null));

        for (var entry : entries) {
            assertEquals(entry, codec.decodeEntry(codec.encodeEntry(entry)));
        }
    }

    @Test
    void completeStandardMessagesAndOpaqueFieldsRoundTripExactly() throws Exception {
        var replay = new ModelReplayState(
                "provider/replay-v1", "{\"opaque\":\"秘密\\nvalue\"}");
        var arguments = MAPPER.readTree("{\"path\":\"目录/文件\",\"nested\":[1,true,null,{\"x\":2.5}]}");
        var cost = CostEstimate.of(
                "USD",
                new BigDecimal("0.0100"),
                new BigDecimal("0.0200"),
                new BigDecimal("0.0030"),
                new BigDecimal("0.0040"));
        var usage = new Usage(10, 20, 3, 4, 37, 8, Optional.of(cost));
        var metadata = ResponseMetadata.of("response-id", "request-id", "provider_done");
        var assistant = new Message.Assistant(
                List.of(
                        new Content.Text("", replay),
                        new Content.Thinking("思考\n第二行", replay),
                        new Content.ToolCall("call-1", "read", arguments)),
                StopReason.TOOL_CALL,
                null,
                usage,
                T1,
                MODEL,
                metadata);
        var user = new Message.User(
                List.of(new Content.Text("你好 🌍"), new Content.Image("image/png", "aGVsbG8=")), T1);
        var toolResult = new Message.ToolResultMessage(
                "call-1", "read", List.of(new Content.Text("结果")), true, T1);

        for (var message : List.<Message>of(user, assistant, toolResult)) {
            var source = new SessionMessageEntry(
                    message.getClass().getSimpleName(), null, T1, StandardAgentMessage.of(message));
            var decoded = assertInstanceOf(
                    SessionMessageEntry.class, codec.decodeEntry(codec.encodeEntry(source)));
            assertEquals(message, decoded.message().message(), message.getClass().getSimpleName());
            assertEquals(source, decoded);
        }

        var decodedAssistant = assertInstanceOf(Message.Assistant.class,
                ((SessionMessageEntry) codec.decodeEntry(codec.encodeEntry(
                        new SessionMessageEntry("assistant", null, T1,
                                StandardAgentMessage.of(assistant)))))
                        .message().message());
        var decodedCall = assertInstanceOf(Content.ToolCall.class, decodedAssistant.content().get(2));
        assertEquals(arguments, decodedCall.arguments());
        assertEquals(replay, ((Content.Text) decodedAssistant.content().getFirst()).replayState());
        assertEquals(cost, decodedAssistant.usage().cost().orElseThrow());
    }

    @Test
    void encodingIsOnePhysicalUtf8LineAndDoesNotAliasToolArguments() throws Exception {
        var arguments = MAPPER.createObjectNode().put("before", true);
        var entry = new SessionMessageEntry(
                "message", null, T1,
                StandardAgentMessage.of(new Message.Assistant(
                        List.of(
                                new Content.Text("line one\nline two"),
                                new Content.ToolCall("call", "tool", arguments)),
                        StopReason.TOOL_CALL, null, Usage.zero(), T1)));

        var encoded = codec.encodeEntry(entry);
        arguments.put("after", true);

        var encodedText = new String(encoded, StandardCharsets.UTF_8);
        assertTrue(encodedText.contains("\\n"));
        assertEquals(-1, encodedText.indexOf('\n'));
        var decoded = (SessionMessageEntry) codec.decodeEntry(encoded);
        var assistant = (Message.Assistant) decoded.message().message();
        var call = (Content.ToolCall) assistant.content().get(1);
        assertEquals(MAPPER.readTree("{\"before\":true}"), call.arguments());
    }

    @Test
    void unknownVersionTypeAndInvalidStructureAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> codec.decodeHeader(bytes("""
                {"type":"session","version":2,"id":"00000000-0000-0000-0000-000000000001",
                 "timestamp":"2026-09-20T00:00:00Z","cwd":"/tmp"}
                """)));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeEntry(bytes("""
                {"type":"future","id":"id","parentId":null,
                 "timestamp":"2026-09-20T00:00:00Z"}
                """)));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeEntry(bytes("""
                {"type":"message","id":"id","parentId":null,
                 "timestamp":"not-an-instant","message":{}}
                """)));
        assertThrows(IOException.class, () -> codec.decodeHeader(bytes("""
                {"type":"session","version":1,"id":"00000000-0000-0000-0000-000000000001",
                 "timestamp":"2026-09-20T00:00:00Z","cwd":"/tmp"} {}
                """)));
    }

    @Test
    void knownRecordsIgnoreAdditionalFieldsWithoutRewritingInput() throws Exception {
        var headerJson = bytes("""
                {"type":"session","version":1,"id":"00000000-0000-0000-0000-000000000001",
                 "timestamp":"2026-09-20T00:00:00Z","cwd":"/tmp","future":"kept-on-disk"}
                """);
        var original = headerJson.clone();

        var header = codec.decodeHeader(headerJson);

        assertEquals(Path.of("/tmp").toAbsolutePath().normalize(), header.cwd());
        assertArrayEquals(original, headerJson);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
