package site.pplee.jcode.aiproviders.openai;

import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.ResponseMetadata;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Request-local redaction of secrets that this provider may have sent.
 * Collects credentials, organization/project, custom header values, the
 * original and clamped cache key, and the session-affinity id. Secrets are
 * never retained in {@link #toString()} and must not be copied into
 * exceptions.
 */
final class OpenAiSecretRedactor {
    static final String PLACEHOLDER = "[redacted]";

    private final List<String> secrets;

    private OpenAiSecretRedactor(List<String> secrets) {
        this.secrets = List.copyOf(secrets);
    }

    static OpenAiSecretRedactor collect(OpenAiProviderConfig config, ModelRequest request) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(request, "request must not be null");
        List<String> values = new ArrayList<>();
        add(values, config.credentials().apiKey());
        config.organization().ifPresent(value -> add(values, value));
        config.project().ifPresent(value -> add(values, value));
        List<String> headerValues = new ArrayList<>();
        config.headers().copyValuesForRedaction(headerValues);
        for (String value : headerValues) {
            add(values, value);
        }
        var cache = request.options().promptCache();
        if (cache.cacheKey() != null) {
            add(values, cache.cacheKey());
            add(values, OpenAiPromptCacheKeys.clamp(cache.cacheKey()));
        }
        add(values, cache.sessionAffinityId());
        values.sort(Comparator.comparingInt(String::length).reversed());
        return new OpenAiSecretRedactor(values);
    }

    String redact(String text) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : secrets) {
            result = result.replace(secret, PLACEHOLDER);
        }
        return result;
    }

    AssistantMessageEvent redactTerminal(AssistantMessageEvent terminal) {
        if (!(terminal instanceof AssistantMessageEvent.Error error)) {
            return terminal;
        }
        Message.Assistant original = error.error();
        String cleanedMessage = redact(original.errorMessage());
        ResponseMetadata cleanedMetadata = redactMetadata(original.metadata());
        boolean messageSame = Objects.equals(original.errorMessage(), cleanedMessage);
        boolean metadataSame = original.metadata().equals(cleanedMetadata);
        if (messageSame && metadataSame) {
            return terminal;
        }
        var rewritten = new Message.Assistant(
                original.content(),
                original.stopReason(),
                cleanedMessage,
                original.usage(),
                original.timestamp(),
                original.sourceModel(),
                cleanedMetadata);
        return new AssistantMessageEvent.Error(error.reason(), rewritten);
    }

    /**
     * Rebuilds correlation fields with secrets replaced. Usage, content, and
     * source model are never taken from metadata and are not modified here.
     * Oversize results after replacement are clamped so construction cannot
     * throw.
     */
    ResponseMetadata redactMetadata(ResponseMetadata metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return metadata == null ? ResponseMetadata.empty() : metadata;
        }
        return ResponseMetadata.of(
                clamp(redact(metadata.responseId().orElse(null)), ResponseMetadata.MAX_RESPONSE_ID_LENGTH),
                clamp(redact(metadata.providerRequestId().orElse(null)),
                        ResponseMetadata.MAX_PROVIDER_REQUEST_ID_LENGTH),
                clamp(redact(metadata.rawTerminalReason().orElse(null)),
                        ResponseMetadata.MAX_RAW_TERMINAL_REASON_LENGTH),
                metadata.failureKind().orElse(null));
    }

    @Override
    public String toString() {
        return "OpenAiSecretRedactor[secrets=" + secrets.size() + "]";
    }

    private static void add(List<String> values, String secret) {
        if (secret == null || secret.isEmpty()) {
            return;
        }
        for (String existing : values) {
            if (existing.equals(secret)) {
                return;
            }
        }
        values.add(secret);
    }

    private static String clamp(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
