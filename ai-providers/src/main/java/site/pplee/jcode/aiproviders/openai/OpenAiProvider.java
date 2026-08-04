package site.pplee.jcode.aiproviders.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.provider.ModelProvider;
import site.pplee.jcode.ai.provider.ProviderAuth;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.ai.stream.AssistantMessageStreams;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * OpenAI provider runtime: the explicit runtime unit for the {@code openai}
 * provider. Owns provider metadata, the static model catalog, auth status,
 * model-ownership checks, and stream behavior; the low-level Responses API
 * adapter stays package-private. Instances are composed into a
 * {@code site.pplee.jcode.ai.provider.Models} collection for dispatch.
 */
public final class OpenAiProvider implements ModelProvider, AutoCloseable {
    private final OpenAiProviderConfig config;
    private final OpenAiResponsesAdapter adapter;

    public OpenAiProvider(OpenAiProviderConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper());
    }

    public OpenAiProvider(OpenAiProviderConfig config, HttpClient httpClient, ObjectMapper objectMapper) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.adapter = new OpenAiResponsesAdapter(config,
                Objects.requireNonNull(httpClient, "httpClient must not be null"),
                Objects.requireNonNull(objectMapper, "objectMapper must not be null"));
    }

    @Override
    public String id() {
        return config.providerId();
    }

    @Override
    public String name() {
        return config.providerName();
    }

    @Override
    public Optional<URI> baseUrl() {
        return Optional.of(config.baseUrl());
    }

    @Override
    public ProviderAuth auth() {
        return OpenAiApiKeyAuth.configured();
    }

    @Override
    public List<Model> models() {
        return config.models();
    }

    @Override
    public boolean supports(ModelRef ref) {
        if (!config.providerId().equals(ref.provider())) {
            return false;
        }
        if (!config.api().equals(ref.api())) {
            return false;
        }
        if (config.allowUnlistedModels()) {
            return true;
        }
        return config.models().stream().anyMatch(model -> model.toRef().equals(ref));
    }

    @Override
    public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
        if (!supports(request.model())) {
            return AssistantMessageStreams.failed(StopReason.ERROR,
                    "unsupported model: " + request.model().provider() + "/" + request.model().api()
                            + "/" + request.model().modelId());
        }
        if (!auth().isConfigured()) {
            return AssistantMessageStreams.failed(StopReason.ERROR, "OpenAI auth is not configured");
        }
        return adapter.stream(request, cancellation);
    }

    @Override
    public void close() {
        adapter.close();
    }
}
