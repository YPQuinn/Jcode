package site.pplee.jcode.aiproviders.openai;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.model.Model;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiHeadersTest {
    private static final String SECRET = "header-secret-value";

    @Test
    void toStringShowsNamesAndCountButNotValues() {
        var headers = OpenAiHeaders.builder()
                .header("X-Trace", SECRET)
                .sensitiveHeader("Proxy-Authorization", "Basic " + SECRET)
                .build();

        assertEquals(List.of("X-Trace", "Proxy-Authorization"), headers.names());
        assertEquals(2, headers.size());
        assertTrue(headers.toString().contains("X-Trace"));
        assertTrue(headers.toString().contains("Proxy-Authorization"));
        assertTrue(headers.toString().contains("sensitive=1"));
        assertFalse(headers.toString().contains(SECRET));
        assertFalse(headers.toString().contains("Basic"));
    }

    @Test
    void reservedAndMalformedHeadersAreRejectedWithoutLeakingValues() {
        var builder = OpenAiHeaders.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.header("Authorization", SECRET));
        assertThrows(IllegalArgumentException.class, () -> builder.header("Host", SECRET));
        assertThrows(IllegalArgumentException.class, () -> builder.header("Content-Type", SECRET));
        assertThrows(IllegalArgumentException.class, () -> builder.header("Accept", SECRET));
        assertThrows(IllegalArgumentException.class, () -> builder.header("Content-Length", SECRET));
        assertThrows(IllegalArgumentException.class, () -> builder.header("OpenAI-Organization", SECRET));
        assertThrows(IllegalArgumentException.class, () -> builder.header("OpenAI-Project", SECRET));
        assertThrows(IllegalArgumentException.class, () -> builder.header("session_id", SECRET));
        assertThrows(IllegalArgumentException.class, () -> builder.header("x-client-request-id", SECRET));
        assertThrows(IllegalArgumentException.class, () -> builder.header("x-session-id", SECRET));
        assertThrows(IllegalArgumentException.class, () -> builder.header("x-session-affinity", SECRET));
        assertThrows(IllegalArgumentException.class, () -> builder.header("Proxy-Authorization", SECRET));
        assertThrows(IllegalArgumentException.class, () -> builder.header("X-Bad\nName", SECRET));
        assertThrows(IllegalArgumentException.class, () -> builder.header("X-Trace", "ok\r\nInjected: 1"));
        assertThrows(IllegalArgumentException.class, () -> builder.header("X-Trace", SECRET + "\u0000"));
        assertThrows(IllegalArgumentException.class, () -> builder.header("X-Trace", SECRET + "\u0007"));
        assertThrows(IllegalArgumentException.class, () -> builder.header("X-Trace", SECRET + "\u007f"));
        assertThrows(IllegalArgumentException.class, () -> builder.header(" ", SECRET));
        assertThrows(IllegalArgumentException.class, () -> builder.header("X Trace", SECRET));
        assertDoesNotThrow(() -> OpenAiHeaders.builder().header("X-Trace", "ok\tvalue").build());
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiHeaders.builder().header("X-Trace", SECRET + "\uD83D"));
        try {
            OpenAiHeaders.builder().header("X-Tr\uD83D", SECRET);
        } catch (IllegalArgumentException e) {
            assertFalse(e.getMessage().contains(SECRET));
            assertFalse(e.getMessage().contains("\uD83D"));
            assertTrue(e.getMessage().contains("malformed UTF-16"));
        }

        try {
            OpenAiHeaders.builder().header("X-Trace", SECRET + "\n");
        } catch (IllegalArgumentException e) {
            assertFalse(e.getMessage().contains(SECRET));
        }
        try {
            OpenAiHeaders.builder().header("X-Trace", SECRET + "\u0000");
        } catch (IllegalArgumentException e) {
            assertFalse(e.getMessage().contains(SECRET));
            assertTrue(e.getMessage().contains("control character"));
        }
    }

    @Test
    void configToStringDoesNotLeakHeaderValues() {
        var headers = OpenAiHeaders.builder()
                .header("X-Trace", SECRET)
                .sensitiveHeader("Proxy-Authorization", "Basic " + SECRET)
                .build();
        var config = OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey("sk-test-secret-123"))
                .models(List.of(new Model("openai", "openai-responses", "gpt-4o-mini", "gpt-4o-mini")))
                .headers(headers)
                .serviceTier(OpenAiServiceTier.FLEX)
                .build();
        assertFalse(config.toString().contains(SECRET));
        assertFalse(config.toString().contains("sk-test-secret-123"));
        assertTrue(config.toString().contains("X-Trace"));
        assertTrue(config.toString().contains("FLEX"));
    }
}
