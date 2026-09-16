package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.message.ModelReplayState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiReplayStateCodecTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void reasoningRoundTripRequiresMatchingDigestAndEncryptedContent() throws Exception {
        var item = MAPPER.readTree(
                "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc\"}");
        var state = OpenAiReplayStateCodec.encodeReasoning("think", item);
        assertEquals(OpenAiReplayStateCodec.REASONING_FORMAT, state.format());
        assertFalse(state.toString().contains("enc"));

        var decoded = OpenAiReplayStateCodec.decodeReasoning(state, "think").orElseThrow();
        assertEquals("rs_1", decoded.get("id").asText());
        assertTrue(OpenAiReplayStateCodec.hasEncryptedContent(decoded));
        assertTrue(OpenAiReplayStateCodec.decodeReasoning(state, "changed").isEmpty());
        decoded.deepCopy();
        assertEquals("enc", OpenAiReplayStateCodec.decodeReasoning(state, "think").orElseThrow()
                .get("encrypted_content").asText());
    }

    @Test
    void messageRoundTripKeepsIdAndPhaseUntilTextChanges() {
        var state = OpenAiReplayStateCodec.encodeMessage("Hello", "msg_1", "final");
        var replay = OpenAiReplayStateCodec.decodeMessage(state, "Hello").orElseThrow();
        assertEquals("msg_1", replay.itemId());
        assertEquals("final", replay.phase());
        assertTrue(OpenAiReplayStateCodec.decodeMessage(state, "Hello!").isEmpty());
    }

    @Test
    void unknownOrMalformedStateIsIgnored() {
        assertTrue(OpenAiReplayStateCodec.decodeReasoning(
                new ModelReplayState("other/v1", "{\"v\":1}"), "x").isEmpty());
        assertTrue(OpenAiReplayStateCodec.decodeReasoning(
                new ModelReplayState(OpenAiReplayStateCodec.REASONING_FORMAT, "{"), "x").isEmpty());
        assertTrue(OpenAiReplayStateCodec.decodeMessage(
                new ModelReplayState(OpenAiReplayStateCodec.MESSAGE_FORMAT, "{\"v\":2,\"digest\":\"x\",\"id\":\"m\"}"),
                "x").isEmpty());
    }
}
