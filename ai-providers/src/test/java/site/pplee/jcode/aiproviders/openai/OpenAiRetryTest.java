package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiRetryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void statusTableAndHeaderOverride() {
        assertTrue(OpenAiRetry.isRetryable(http(408)));
        assertTrue(OpenAiRetry.isRetryable(http(409)));
        assertTrue(OpenAiRetry.isRetryable(http(429)));
        assertFalse(OpenAiRetry.isRetryable(http(499)));
        assertTrue(OpenAiRetry.isRetryable(http(500)));
        assertTrue(OpenAiRetry.isRetryable(http(503)));
        assertTrue(OpenAiRetry.isRetryable(http(599)));
        assertFalse(OpenAiRetry.isRetryable(http(600)));
        assertFalse(OpenAiRetry.isRetryable(http(400)));
        assertFalse(OpenAiRetry.isRetryable(http(401)));
        assertFalse(OpenAiRetry.isRetryable(http(403)));
        assertTrue(OpenAiRetry.isRetryable(http(400, Map.of("x-should-retry", "true"))));
        assertFalse(OpenAiRetry.isRetryable(http(429, Map.of("x-should-retry", "false"))));
        assertTrue(OpenAiRetry.isRetryableTransport());
        assertTrue(OpenAiRetry.isSynchronousTransportFailure(
                new java.io.UncheckedIOException(new java.io.IOException("io"))));
        assertFalse(OpenAiRetry.isSynchronousTransportFailure(new IllegalArgumentException("bad request")));
        assertFalse(OpenAiRetry.isSynchronousTransportFailure(new RuntimeException("boom")));
    }

    @Test
    void delayPrefersRetryAfterMsThenSecondsThenHttpDateThenBackoff() {
        var policy = OpenAiRetryPolicy.builder()
                .maxRetries(1)
                .maxServerDelay(Duration.ofSeconds(10))
                .maxBackoff(Duration.ofSeconds(8))
                .jitterRange(1.0d, 1.0d)
                .build();

        var fromMs = OpenAiRetry.delayForHttp(
                http(429, Map.of("retry-after-ms", "1500", "retry-after", "9")), 0, policy, NOW, 0.0d);
        assertEquals(OpenAiRetry.Kind.SERVER, fromMs.kind());
        assertEquals(Duration.ofMillis(1500), fromMs.duration());

        var fromSeconds = OpenAiRetry.delayForHttp(
                http(429, Map.of("retry-after", "2")), 0, policy, NOW, 0.0d);
        assertEquals(Duration.ofSeconds(2), fromSeconds.duration());

        var fromDate = OpenAiRetry.delayForHttp(
                http(429, Map.of("retry-after", "Thu, 01 Jan 2026 00:00:03 GMT")), 0, policy, NOW, 0.0d);
        assertEquals(Duration.ofSeconds(3), fromDate.duration());

        var backoff = OpenAiRetry.delayForHttp(
                http(429, Map.of("retry-after-ms", "nope", "retry-after", "soon")), 0, policy, NOW, 0.0d);
        assertEquals(OpenAiRetry.Kind.BACKOFF, backoff.kind());
        assertEquals(Duration.ofMillis(500), backoff.duration());
    }

    @Test
    void illegalOrNegativeServerDelayFallsBackOrZeroes() {
        var policy = OpenAiRetryPolicy.of(1);
        var negative = OpenAiRetry.delayForHttp(
                http(429, Map.of("retry-after-ms", "-40")), 0, policy, NOW, 0.0d);
        assertEquals(Duration.ZERO, negative.duration());
        assertFalse(negative.exceedsServerMax());

        var datePast = OpenAiRetry.delayForHttp(
                http(429, Map.of("retry-after", "Wed, 31 Dec 2025 00:00:00 GMT")), 0, policy, NOW, 0.0d);
        assertEquals(Duration.ZERO, datePast.duration());
    }

    @Test
    void serverDelayAboveMaximumFailsImmediately() {
        var policy = OpenAiRetryPolicy.builder()
                .maxRetries(1)
                .maxServerDelay(Duration.ofSeconds(1))
                .build();
        var delay = OpenAiRetry.delayForHttp(
                http(429, Map.of("retry-after", "120")), 0, policy, NOW, 0.0d);
        assertTrue(delay.exceedsServerMax());
    }

    @Test
    void budgetComparesPlannedWaitToRemainingTime() {
        var started = NOW;
        assertFalse(OpenAiRetry.exceedsRemainingBudget(
                Duration.ofMillis(10), started, NOW, Optional.empty()));
        assertTrue(OpenAiRetry.exceedsRemainingBudget(
                Duration.ofMillis(10), started, NOW, Optional.of(Duration.ZERO)));
        assertTrue(OpenAiRetry.exceedsRemainingBudget(
                Duration.ZERO, started, NOW, Optional.of(Duration.ZERO)));
        assertTrue(OpenAiRetry.exceedsRemainingBudget(
                Duration.ofSeconds(2), started, NOW.plusSeconds(1), Optional.of(Duration.ofSeconds(2))));
        assertTrue(OpenAiRetry.budgetExhausted(started, NOW.plusSeconds(1), Optional.of(Duration.ofSeconds(1))));
        assertFalse(OpenAiRetry.budgetExhausted(started, NOW.plusMillis(500), Optional.of(Duration.ofSeconds(1))));
        assertEquals(Duration.ZERO, OpenAiRetry.elapsedSince(NOW.plusSeconds(5), NOW));
        assertFalse(OpenAiRetry.exceedsRemainingBudget(
                Duration.ofMillis(100), NOW.plusSeconds(5), NOW, Optional.of(Duration.ofSeconds(1))));
    }

    private static OpenAiHttpError http(int status) {
        return http(status, Map.of());
    }

    private static OpenAiHttpError http(int status, Map<String, String> headers) {
        Map<String, List<String>> mapped = headers.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue())));
        return OpenAiHttpError.parse(
                status,
                HttpHeaders.of(mapped, (a, b) -> true),
                "{\"error\":{\"message\":\"x\"}}".getBytes(StandardCharsets.UTF_8),
                MAPPER);
    }
}
