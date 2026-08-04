package site.pplee.jcode.aiproviders.openai;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.aiproviders.openai.support.MutableCancellationSignal;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider-runtime contract tests for {@link OpenAiProvider}: metadata,
 * model-ownership checks, redacted secret display, and no-sync-throw failure
 * streams.
 */
class OpenAiProviderTest {
    private static final Model GPT = new Model("openai", "openai-responses", "gpt-4o-mini", "gpt-4o-mini");
    private static final String API_KEY = "sk-test-secret-123";

    private static OpenAiProviderConfig config() {
        return OpenAiProviderConfig.responses(OpenAiCredentials.apiKey(API_KEY), List.of(GPT));
    }

    private static ModelRequest request(ModelRef ref) {
        return new ModelRequest(ref, "sys", List.of(), List.of());
    }

    @Test
    void exposesProviderMetadata() {
        var provider = new OpenAiProvider(config());
        assertEquals("openai", provider.id());
        assertEquals("OpenAI", provider.name());
        assertTrue(provider.baseUrl().isPresent());
        assertEquals("https://api.openai.com/v1", provider.baseUrl().orElseThrow().toString());
        assertEquals(List.of(GPT), provider.models());
        assertTrue(provider.auth().isConfigured());
        assertTrue(provider.auth().diagnostic().isEmpty());
    }

    @Test
    void supportsChecksProviderApiAndCatalog() {
        var provider = new OpenAiProvider(config());
        assertTrue(provider.supports(GPT.toRef()));
        assertFalse(provider.supports(new ModelRef("openai", "openai-responses", "gpt-999")));
        assertFalse(provider.supports(new ModelRef("anthropic", "anthropic-messages", "claude-1")));
        assertFalse(provider.supports(new ModelRef("openai", "openai-chat-completions", "gpt-4o-mini")));
    }

    @Test
    void allowUnlistedModelsAcceptsUncataloguedIds() {
        var config = OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(GPT))
                .allowUnlistedModels(true)
                .build();
        var provider = new OpenAiProvider(config);
        assertTrue(provider.supports(new ModelRef("openai", "openai-responses", "gpt-999")));
    }

    @Test
    void credentialsAndConfigToStringAreRedacted() {
        var credentials = OpenAiCredentials.apiKey(API_KEY);
        var config = config();
        assertFalse(credentials.toString().contains(API_KEY));
        assertFalse(config.toString().contains(API_KEY));
        assertTrue(credentials.toString().contains("redacted"));
        assertTrue(config.toString().contains("redacted"));
    }

    @Test
    void credentialsRejectBlankKey() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OpenAiCredentials.apiKey(" "));
    }

    @Test
    void providerIsNotAModelClient() {
        assertFalse(ModelClient.class.isAssignableFrom(OpenAiProvider.class));
    }

    @Test
    void streamForUnsupportedModelReturnsStartError() throws Exception {
        var provider = new OpenAiProvider(config());
        var stream = provider.stream(
                request(new ModelRef("openai", "openai-responses", "gpt-999")),
                new MutableCancellationSignal());
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, stream.take());
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("unsupported model"));
    }

    @Test
    void streamForCancelledSignalReturnsAborted() throws Exception {
        var provider = new OpenAiProvider(config());
        var cancellation = new MutableCancellationSignal();
        cancellation.cancel();
        var stream = provider.stream(request(GPT.toRef()), cancellation);
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, stream.take());
        assertEquals(StopReason.ABORTED, error.reason());
    }

    @Test
    void configRejectsCatalogModelWithWrongApi() {
        // Same model id, but the catalog entry speaks a different API dialect.
        var wrongApi = new Model("openai", "openai-chat-completions", "gpt-4o-mini", "gpt-4o-mini");
        assertThrows(IllegalArgumentException.class, () -> OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(wrongApi))
                .build());
    }

    @Test
    void configRejectsCatalogModelFromAnotherProvider() {
        var foreign = new Model("anthropic", "anthropic-messages", "gpt-4o-mini", "gpt-4o-mini");
        assertThrows(IllegalArgumentException.class, () -> OpenAiProviderConfig.builder()
                .credentials(OpenAiCredentials.apiKey(API_KEY))
                .models(List.of(foreign))
                .build());
    }

    @Test
    void supportsMatchesFullModelRefAgainstCatalog() {
        var provider = new OpenAiProvider(config());
        // Same model id as the catalog entry, but a different provider dimension.
        assertFalse(provider.supports(new ModelRef("other", "openai-responses", "gpt-4o-mini")));
        // Same model id, correct provider and api: supported.
        assertTrue(provider.supports(new ModelRef("openai", "openai-responses", "gpt-4o-mini")));
    }

    @Test
    void streamForUnsupportedOffThinkingReturnsStartError() throws Exception {
        // Default config has no capabilities, so OFF cannot be satisfied explicitly.
        var provider = new OpenAiProvider(config());
        var request = new ModelRequest(GPT.toRef(), "sys", List.of(), List.of(), ThinkingLevel.OFF);
        var stream = provider.stream(request, new MutableCancellationSignal());
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, stream.take());
        assertEquals(StopReason.ERROR, error.reason());
        assertTrue(error.error().errorMessage().contains("mapping failed"));
    }

    @Test
    void streamForMismatchedApiReturnsStartError() throws Exception {
        var provider = new OpenAiProvider(config());
        var stream = provider.stream(
                request(new ModelRef("openai", "openai-chat-completions", "gpt-4o-mini")),
                new MutableCancellationSignal());
        assertInstanceOf(AssistantMessageEvent.Start.class, stream.take());
        var error = assertInstanceOf(AssistantMessageEvent.Error.class, stream.take());
        assertEquals(StopReason.ERROR, error.reason());
    }
}
