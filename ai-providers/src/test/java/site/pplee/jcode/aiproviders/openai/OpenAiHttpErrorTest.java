package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.client.PromptCacheOptions;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ThinkingLevel;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiHttpErrorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "sk-test-secret-123";
    private static final Model GPT = new Model("openai", "openai-responses", "gpt-4o-mini", "gpt-4o-mini");

    @Test
    void standardEnvelopeIsPreferred() {
        var error = OpenAiHttpError.parse(
                429,
                headers(Map.of(
                        "x-should-retry", List.of("true"),
                        "retry-after-ms", List.of("250"),
                        "x-request-id", List.of("req_1"))),
                """
                {"error":{"message":"slow down","type":"rate_limit_error","code":"rate_limit_exceeded"}}
                """.getBytes(StandardCharsets.UTF_8),
                MAPPER);
        assertEquals(429, error.status());
        assertEquals(Boolean.TRUE, error.shouldRetryOverride().orElseThrow());
        assertEquals("250", error.retryAfterMs().orElseThrow());
        assertEquals("req_1", error.requestId().orElseThrow());
        assertEquals("rate_limit_exceeded", error.errorCode().orElseThrow());
        assertEquals("slow down", error.errorMessage().orElseThrow());
        assertEquals("rate_limit_error", error.errorType().orElseThrow());
        assertEquals("HTTP 429 rate_limit_error [rate_limit_exceeded]: slow down", error.diagnosticMessage());
        assertFalse(error.toString().contains("slow down"));
        assertFalse(error.toString().contains("req_1"));
    }

    @Test
    void nonStandardBodyUsesStatusAndTruncatedText() {
        var error = OpenAiHttpError.parse(
                403, headers(Map.of()), "gateway blocked".getBytes(StandardCharsets.UTF_8), MAPPER);
        assertTrue(error.errorMessage().isEmpty());
        assertEquals("HTTP 403: gateway blocked", error.diagnosticMessage());
    }

    @Test
    void oversizeBodyIsCappedAt4KiB() {
        byte[] raw = "x".repeat(OpenAiHttpError.MAX_BODY_BYTES + 80).getBytes(StandardCharsets.UTF_8);
        var error = OpenAiHttpError.parse(500, headers(Map.of()), raw, MAPPER);
        assertTrue(error.bodyTruncated());
        assertTrue(error.truncatedBody().getBytes(StandardCharsets.UTF_8).length <= OpenAiHttpError.MAX_BODY_BYTES);
        assertTrue(error.diagnosticMessage().contains("HTTP 500"));
        assertTrue(error.diagnosticMessage().contains("[truncated]"));
        assertTrue(error.diagnosticMessage().length() < raw.length);
    }

    @Test
    void emptyBodyKeepsStatusOnly() {
        var error = OpenAiHttpError.parse(401, headers(Map.of()), new byte[0], MAPPER);
        assertEquals("HTTP 401", error.diagnosticMessage());
    }

    @Test
    void shouldRetryOverrideIgnoresUnknownValues() {
        var error = OpenAiHttpError.parse(
                400,
                headers(Map.of("x-should-retry", List.of("yes"))),
                "{\"error\":{\"message\":\"no\"}}".getBytes(StandardCharsets.UTF_8),
                MAPPER);
        assertTrue(error.shouldRetryOverride().isEmpty());
        assertFalse(OpenAiRetry.isRetryable(error));
    }

    @Test
    void diagnosticsDoNotIncludeRequestSecrets() {
        var error = OpenAiHttpError.parse(
                401,
                headers(Map.of(
                        "authorization", List.of("Bearer " + SECRET),
                        "openai-organization", List.of("org-secret"),
                        "x-request-id", List.of("req_safe"))),
                "{\"error\":{\"message\":\"bad key\"}}".getBytes(StandardCharsets.UTF_8),
                MAPPER);
        assertEquals("HTTP 401: bad key", error.diagnosticMessage());
        assertFalse(error.diagnosticMessage().contains(SECRET));
        assertFalse(error.diagnosticMessage().contains("org-secret"));
        assertFalse(error.toString().contains(SECRET));
        assertFalse(error.toString().contains("Bearer"));
        assertEquals("req_safe", error.requestId().orElseThrow());
    }

    @Test
    void productionParseRedactsRetainedAccessorsAndDiagnostic() {
        String org = "org-secret-value";
        String project = "proj-secret-value";
        String cache = "cache-secret-value";
        String session = "session-secret-value";
        String header = "header-secret-value";
        var config = OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(SECRET))
                .models(List.of(GPT))
                .organization(org)
                .project(project)
                .headers(OpenAiHeaders.builder().header("X-Trace", header).build())
                .build();
        var request = new ModelRequest(GPT.toRef(), "sys", List.of(), List.of(),
                ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withPromptCache(
                        PromptCacheOptions.defaults().withCacheKey(cache).withSessionAffinityId(session)));
        var redactor = OpenAiSecretRedactor.collect(config, request);
        String echoed = SECRET + " " + org + " " + project + " " + cache + " " + session + " " + header;
        var error = OpenAiHttpError.parse(
                401,
                headers(Map.of(
                        "x-should-retry", List.of("false"),
                        "retry-after-ms", List.of("250"),
                        "x-request-id", List.of(session))),
                ("{\"error\":{\"message\":\"bad key " + echoed + "\",\"type\":\"auth\",\"code\":\"invalid_api_key\"}}")
                        .getBytes(StandardCharsets.UTF_8),
                MAPPER,
                redactor);
        assertEquals(401, error.status());
        assertEquals(Boolean.FALSE, error.shouldRetryOverride().orElseThrow());
        assertEquals("250", error.retryAfterMs().orElseThrow());
        assertFalse(error.truncatedBody().contains(SECRET));
        assertFalse(error.truncatedBody().contains(org));
        assertFalse(error.truncatedBody().contains(project));
        assertFalse(error.truncatedBody().contains(cache));
        assertFalse(error.truncatedBody().contains(session));
        assertFalse(error.truncatedBody().contains(header));
        assertFalse(error.errorMessage().orElseThrow().contains(SECRET));
        assertFalse(error.errorMessage().orElseThrow().contains(session));
        assertFalse(error.requestId().orElseThrow().contains(session));
        assertTrue(error.requestId().orElseThrow().contains("[redacted]"));
        assertFalse(error.diagnosticMessage().contains(SECRET));
        assertFalse(error.diagnosticMessage().contains(org));
        assertFalse(error.diagnosticMessage().contains(cache));
        assertFalse(error.diagnosticMessage().contains(session));
        assertTrue(error.diagnosticMessage().contains("[redacted]"));
        assertFalse(OpenAiRetry.isRetryable(error));
    }

    @Test
    void retryDecisionDoesNotReadErrorMessage() {
        var retryable = OpenAiHttpError.parse(
                400,
                headers(Map.of("x-should-retry", List.of("true"))),
                "{\"error\":{\"message\":\"this text says do not retry\"}}".getBytes(StandardCharsets.UTF_8),
                MAPPER);
        var notRetryable = OpenAiHttpError.parse(
                429,
                headers(Map.of("x-should-retry", List.of("false"))),
                "{\"error\":{\"message\":\"please retry now\"}}".getBytes(StandardCharsets.UTF_8),
                MAPPER);
        assertTrue(OpenAiRetry.isRetryable(retryable));
        assertFalse(OpenAiRetry.isRetryable(notRetryable));
    }

    private static HttpHeaders headers(Map<String, List<String>> values) {
        return HttpHeaders.of(values, (a, b) -> true);
    }
}
