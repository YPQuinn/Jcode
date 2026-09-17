package site.pplee.jcode.aiproviders.openai;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.model.Model;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiRetryPolicyTest {
    private static final String API_KEY = "sk-test-secret-123";

    @Test
    void disabledKeepsSingleAttemptDefaults() {
        var policy = OpenAiRetryPolicy.disabled();
        assertSame(policy, OpenAiRetryPolicy.defaults());
        assertEquals(0, policy.maxRetries());
        assertEquals(Duration.ofSeconds(60), policy.maxServerDelay());
        assertEquals(Duration.ofSeconds(8), policy.maxBackoff());
        assertEquals(0.75d, policy.jitterMin());
        assertEquals(1.0d, policy.jitterMax());
        assertTrue(policy.totalBudget().isEmpty());
    }

    @Test
    void ofMaxRetriesKeepsOtherDefaults() {
        var policy = OpenAiRetryPolicy.of(2);
        assertEquals(2, policy.maxRetries());
        assertEquals(OpenAiRetryPolicy.DEFAULT_MAX_SERVER_DELAY, policy.maxServerDelay());
        assertEquals(OpenAiRetryPolicy.DEFAULT_MAX_BACKOFF, policy.maxBackoff());
    }

    @Test
    void builderAcceptsBoundedZeroValues() {
        var policy = OpenAiRetryPolicy.builder()
                .maxRetries(0)
                .maxServerDelay(Duration.ZERO)
                .maxBackoff(Duration.ZERO)
                .jitterRange(0.0d, 0.0d)
                .totalBudget(Duration.ZERO)
                .build();
        assertEquals(0, policy.maxRetries());
        assertEquals(Duration.ZERO, policy.maxServerDelay());
        assertEquals(Duration.ZERO, policy.maxBackoff());
        assertEquals(0.0d, policy.jitterMin());
        assertEquals(Duration.ZERO, policy.totalBudget().orElseThrow());
    }

    @Test
    void validationRejectsNegativeAndUnboundedValues() {
        assertThrows(IllegalArgumentException.class, () -> OpenAiRetryPolicy.of(-1));
        assertThrows(IllegalArgumentException.class, () -> OpenAiRetryPolicy.of(33));
        assertThrows(IllegalArgumentException.class, () -> OpenAiRetryPolicy.builder()
                .maxServerDelay(Duration.ofSeconds(-1)).build());
        assertThrows(IllegalArgumentException.class, () -> OpenAiRetryPolicy.builder()
                .maxBackoff(Duration.ofMinutes(11)).build());
        assertThrows(IllegalArgumentException.class, () -> OpenAiRetryPolicy.builder()
                .jitterRange(1.1d, 1.2d).build());
        assertThrows(IllegalArgumentException.class, () -> OpenAiRetryPolicy.builder()
                .jitterRange(0.8d, 0.2d).build());
        assertThrows(IllegalArgumentException.class, () -> OpenAiRetryPolicy.builder()
                .jitterRange(Double.NaN, 1.0d).build());
        assertThrows(IllegalArgumentException.class, () -> OpenAiRetryPolicy.builder()
                .totalBudget(Duration.ofMinutes(31)).build());
        assertThrows(IllegalArgumentException.class, () -> OpenAiRetryPolicy.builder()
                .maxServerDelay(null).build());
    }

    @Test
    void toStringDoesNotLeakCredentials() {
        var policy = OpenAiRetryPolicy.of(1);
        var config = OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(new Model("openai", "openai-responses", "gpt-4o-mini", "gpt-4o-mini")))
                .organization("org-secret")
                .project("proj-secret")
                .retryPolicy(policy)
                .build();
        assertTrue(config.retryPolicy().equals(policy));
        assertEquals(0, OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of())
                .build()
                .retryPolicy()
                .maxRetries());
        assertFalse(policy.toString().contains(API_KEY));
        assertFalse(config.toString().contains(API_KEY));
        assertFalse(config.toString().contains("org-secret"));
        assertFalse(config.toString().contains("proj-secret"));
        assertTrue(config.toString().contains("retryPolicy="));
        assertTrue(config.toString().contains("maxRetries=1"));
    }
}
