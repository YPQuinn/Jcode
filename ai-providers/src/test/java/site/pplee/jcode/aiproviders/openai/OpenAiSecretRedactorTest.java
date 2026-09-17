package site.pplee.jcode.aiproviders.openai;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.client.PromptCacheOptions;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.ResponseMetadata;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;

import java.time.Instant;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiSecretRedactorTest {
    private static final Model GPT = new Model("openai", "openai-responses", "gpt-4o-mini", "gpt-4o-mini");

    @Test
    void redactsEveryCollectedSecretAndHidesThemFromToString() {
        String key = "sk-test-secret-123";
        String org = "org-secret-value";
        String project = "proj-secret-value";
        String cache = "cache-secret-value";
        String session = "session-secret-value";
        String header = "header-secret-value";
        var config = OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(key))
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
        String cleaned = redactor.redact("see " + key + " " + org + " " + project + " " + cache + " " + session + " " + header);
        assertFalse(cleaned.contains(key));
        assertFalse(cleaned.contains(org));
        assertFalse(cleaned.contains(project));
        assertFalse(cleaned.contains(cache));
        assertFalse(cleaned.contains(session));
        assertFalse(cleaned.contains(header));
        assertTrue(cleaned.contains("[redacted]"));
        assertFalse(redactor.toString().contains(key));
        assertFalse(redactor.toString().contains(header));
        assertEquals("OpenAiSecretRedactor[secrets=6]", redactor.toString());
    }

    @Test
    void redactsOriginalAndClampedCacheKey() {
        String longKey = "k".repeat(OpenAiPromptCacheKeys.MAX_CODE_POINTS + 16);
        String clamped = OpenAiPromptCacheKeys.clamp(longKey);
        var config = OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey("sk-test-secret-123"))
                .models(List.of(GPT))
                .build();
        var request = new ModelRequest(GPT.toRef(), "sys", List.of(), List.of(),
                ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withPromptCache(
                        PromptCacheOptions.defaults().withCacheKey(longKey)));
        var redactor = OpenAiSecretRedactor.collect(config, request);
        String cleaned = redactor.redact("orig=" + longKey + " sent=" + clamped);
        assertFalse(cleaned.contains(longKey));
        assertFalse(cleaned.contains(clamped));
        assertTrue(cleaned.contains("[redacted]"));
    }

    @Test
    void redactsErrorMetadataWithoutTouchingUsageOrContent() {
        String key = "sk-test-secret-123";
        String session = "session-secret-value";
        String cache = "cache-secret-value";
        var config = OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(key))
                .models(List.of(GPT))
                .build();
        var request = new ModelRequest(GPT.toRef(), "sys", List.of(), List.of(),
                ThinkingLevel.PROVIDER_DEFAULT,
                ModelRequestOptions.defaults().withPromptCache(
                        PromptCacheOptions.defaults().withCacheKey(cache).withSessionAffinityId(session)));
        var redactor = OpenAiSecretRedactor.collect(config, request);
        var usage = new Usage(3, 1, 0, 0, 4);
        var original = new Message.Assistant(
                List.of(),
                StopReason.ERROR,
                "boom " + key,
                usage,
                Instant.parse("2026-01-01T00:00:00Z"),
                GPT.toRef(),
                ResponseMetadata.of(session, key, cache));
        var terminal = redactor.redactTerminal(new AssistantMessageEvent.Error(StopReason.ERROR, original));
        var error = (AssistantMessageEvent.Error) terminal;
        assertFalse(error.error().errorMessage().contains(key));
        assertFalse(error.error().metadata().responseId().orElseThrow().contains(session));
        assertFalse(error.error().metadata().providerRequestId().orElseThrow().contains(key));
        assertFalse(error.error().metadata().rawTerminalReason().orElseThrow().contains(cache));
        assertEquals(usage, error.error().usage());
        assertEquals(original.content(), error.error().content());
        assertEquals(original.sourceModel(), error.error().sourceModel());
    }
}
