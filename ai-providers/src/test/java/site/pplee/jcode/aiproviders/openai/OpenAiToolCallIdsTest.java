package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec tests for the versioned {@code oai1:} tool-call id format: round
 * trips, malformed-encoded fail-safe (never raw fallback), raw-id safety
 * validation, and replay item-id rules (include only when already valid,
 * never fabricate).
 */
class OpenAiToolCallIdsTest {

    @Test
    void encodesAndDecodesBothParts() {
        var encoded = OpenAiToolCallIds.encode("call_abc", "fc_123");
        assertTrue(encoded.startsWith("oai1:"));
        var decoded = OpenAiToolCallIds.decode(encoded).orElseThrow();
        assertEquals("call_abc", decoded.callId());
        assertEquals("fc_123", decoded.itemId());
        assertEquals("call_abc", OpenAiToolCallIds.callId(encoded));
        assertEquals("fc_123", OpenAiToolCallIds.itemId(encoded));
    }

    @Test
    void encodesWithoutItemId() {
        var encoded = OpenAiToolCallIds.encode("call_abc", null);
        var decoded = OpenAiToolCallIds.decode(encoded).orElseThrow();
        assertEquals("call_abc", decoded.callId());
        assertNull(decoded.itemId());
        assertNull(OpenAiToolCallIds.itemId(encoded));
    }

    @Test
    void rawIdFallsBackToCallIdOnly() {
        assertEquals("call_raw_1", OpenAiToolCallIds.callId("call_raw_1"));
        assertNull(OpenAiToolCallIds.itemId("call_raw_1"));
        assertTrue(OpenAiToolCallIds.decode("call_raw_1").isEmpty());
    }

    @Test
    void unsafeRawIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiToolCallIds.callId("bad id with spaces"));
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiToolCallIds.callId("call_" + "x".repeat(100)));
    }

    @Test
    void malformedEncodedIdFailsSafeInsteadOfRawFallback() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiToolCallIds.decode("oai1:!!!not-base64!!!"));
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiToolCallIds.callId("oai1:!!!not-base64!!!"));
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiToolCallIds.decode("oai1:"));
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiToolCallIds.decode("oai1:abc"));
    }

    @Test
    void encodedPayloadWithNonJsonFailsSafe() {
        String encoded = "oai1:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not json".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> OpenAiToolCallIds.decode(encoded));
    }

    @Test
    void encodedPayloadWithInvalidCallIdFailsSafe() throws Exception {
        var mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("callId", "bad id");
        String encoded = "oai1:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsString(node).getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> OpenAiToolCallIds.decode(encoded));
    }

    @Test
    void encodeRejectsUnsafeCallId() {
        assertThrows(IllegalArgumentException.class, () -> OpenAiToolCallIds.encode(" ", "fc_1"));
        assertThrows(IllegalArgumentException.class, () -> OpenAiToolCallIds.encode("bad id", "fc_1"));
        assertThrows(IllegalArgumentException.class, () -> OpenAiToolCallIds.encode("call_" + "x".repeat(100), "fc_1"));
    }

    @Test
    void validItemIdKeepsOnlyAlreadyValidIds() {
        assertEquals("fc_123", OpenAiToolCallIds.validItemId("fc_123"));
        assertNull(OpenAiToolCallIds.validItemId("abc"));
        assertNull(OpenAiToolCallIds.validItemId("fc_" + "x".repeat(100)));
        assertNull(OpenAiToolCallIds.validItemId(" "));
        assertNull(OpenAiToolCallIds.validItemId(null));
    }

    @Test
    void validItemIdOmitsUnsafeCharsInsteadOfSanitizing() {
        assertNull(OpenAiToolCallIds.validItemId("fc_a b"));
        assertNull(OpenAiToolCallIds.validItemId("fc_a/b"));
    }

    @Test
    void roundTripsSafeIdsWithoutDelimiterAmbiguity() {
        // Safe ids containing both delimiter-ish chars must survive the codec unchanged.
        var weirdButSafe = "call_abc-xyz_9";
        var encoded = OpenAiToolCallIds.encode(weirdButSafe, "fc_99");
        assertEquals(weirdButSafe, OpenAiToolCallIds.callId(encoded));
        assertEquals("fc_99", OpenAiToolCallIds.itemId(encoded));
    }

    @Test
    void hashedForeignCallIdIsDeterministicAndBounded() {
        String hashed = OpenAiToolCallIds.hashedForeignCallId("bad id with spaces");
        assertEquals(hashed, OpenAiToolCallIds.hashedForeignCallId("bad id with spaces"));
        assertTrue(hashed.startsWith("call_jcode_"));
        assertTrue(hashed.length() <= 64);
        assertTrue(OpenAiToolCallIds.isSafeId(hashed));
    }

    @Test
    void rejectsUnsafeCallIdInsteadOfRoundTripping() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiToolCallIds.encode("call_|:with$special#chars", "fc_99"));
    }
}
