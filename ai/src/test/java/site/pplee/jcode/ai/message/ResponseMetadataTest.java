package site.pplee.jcode.ai.message;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseMetadataTest {

    @Test
    void emptyIsAbsentAndReusable() {
        var empty = ResponseMetadata.empty();
        assertTrue(empty.isEmpty());
        assertEquals(Optional.empty(), empty.responseId());
        assertEquals(Optional.empty(), empty.providerRequestId());
        assertEquals(Optional.empty(), empty.rawTerminalReason());
        assertEquals(empty, ResponseMetadata.of(null, "  ", ""));
        assertEquals(empty, new ResponseMetadata(Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void blankValuesBecomeAbsent() {
        var metadata = ResponseMetadata.of("  ", "\n", "\t");
        assertTrue(metadata.isEmpty());
    }

    @Test
    void acceptsValuesAtMaxLength() {
        String id = "r".repeat(ResponseMetadata.MAX_RESPONSE_ID_LENGTH);
        String requestId = "q".repeat(ResponseMetadata.MAX_PROVIDER_REQUEST_ID_LENGTH);
        String reason = "s".repeat(ResponseMetadata.MAX_RAW_TERMINAL_REASON_LENGTH);
        var metadata = ResponseMetadata.of(id, requestId, reason);
        assertEquals(id, metadata.responseId().orElseThrow());
        assertEquals(requestId, metadata.providerRequestId().orElseThrow());
        assertEquals(reason, metadata.rawTerminalReason().orElseThrow());
    }

    @Test
    void rejectsOversizeFields() {
        assertThrows(IllegalArgumentException.class, () -> ResponseMetadata.of(
                "r".repeat(ResponseMetadata.MAX_RESPONSE_ID_LENGTH + 1), null, null));
        assertThrows(IllegalArgumentException.class, () -> ResponseMetadata.of(
                null, "q".repeat(ResponseMetadata.MAX_PROVIDER_REQUEST_ID_LENGTH + 1), null));
        assertThrows(IllegalArgumentException.class, () -> ResponseMetadata.of(
                null, null, "s".repeat(ResponseMetadata.MAX_RAW_TERMINAL_REASON_LENGTH + 1)));
    }

    @Test
    void rejectsNullOptionalComponents() {
        assertThrows(NullPointerException.class,
                () -> new ResponseMetadata(null, Optional.empty(), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new ResponseMetadata(Optional.empty(), null, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new ResponseMetadata(Optional.empty(), Optional.empty(), null));
    }

    @Test
    void toStringRedactsValues() {
        var metadata = ResponseMetadata.of("resp_secret", "req_secret", "incomplete.content_filter");
        String text = metadata.toString();
        assertTrue(text.contains("responseId=present"));
        assertTrue(text.contains("providerRequestId=present"));
        assertTrue(text.contains("rawTerminalReason=present"));
        assertFalse(text.contains("resp_secret"));
        assertFalse(text.contains("req_secret"));
        assertFalse(text.contains("content_filter"));
        assertEquals(
                "ResponseMetadata[responseId=absent, providerRequestId=absent, rawTerminalReason=absent]",
                ResponseMetadata.empty().toString());
    }
}
