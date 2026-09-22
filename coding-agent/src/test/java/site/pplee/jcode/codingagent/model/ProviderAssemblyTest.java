package site.pplee.jcode.codingagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.aiproviders.openai.OpenAiCredentials;
import site.pplee.jcode.aiproviders.openai.OpenAiModelCapabilities;
import site.pplee.jcode.aiproviders.openai.OpenAiResponsesCompatibility;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderAssemblyTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void parsesFixedProviderDefinitionsAndSdkReplacementIsWholeDefinition() throws Exception {
        var path = directory.resolve("models.json");
        Files.writeString(path, """
                {
                  "providers": {
                    "local": {
                      "adapter": "openai-responses",
                      "name": "Local",
                      "api": "responses",
                      "baseUrl": "http://127.0.0.1:8080/v1",
                      "compatibility": {
                        "endpointProfile": "openai_no_session",
                        "maxOutputTokens": true
                      },
                      "apiKeyEnv": "LOCAL_KEY",
                      "allowUnlistedModels": false,
                      "models": [{
                        "id": "one",
                        "capabilities": {"temperature": true},
                        "profile": {"contextWindow": 8192, "maxOutputTokens": 1024}
                      }]
                    }
                  }
                }
                """);

        var loaded = ProviderDefinitions.load(path, MAPPER);
        assertTrue(loaded.valid());
        var definition = loaded.definitions().getFirst();
        assertEquals("local", definition.providerId());
        assertEquals("LOCAL_KEY", definition.apiKeyEnvironmentVariable().orElseThrow());
        assertEquals(8192,
                definition.models().getFirst().profile().contextWindow().getAsInt());
        assertEquals(1024,
                definition.models().getFirst().profile().maxOutputTokens().getAsInt());

        var replacement = definition("local", "replacement");
        var merged = ProviderDefinitions.merge(loaded.definitions(), List.of(replacement));
        assertEquals(List.of("replacement"),
                merged.getFirst().models().stream().map(ProviderDefinition.DefinedModel::id).toList());
        assertThrows(IllegalArgumentException.class,
                () -> ProviderDefinitions.merge(List.of(), List.of(replacement, replacement)));
    }

    @Test
    void providerDefinitionsRejectTrailingJson() throws Exception {
        var path = directory.resolve("trailing-models.json");
        Files.writeString(path, """
                {"providers":{}}
                {"providers":{}}
                """);

        var loaded = ProviderDefinitions.load(path, MAPPER);

        assertFalse(loaded.valid());
        assertTrue(loaded.definitions().isEmpty());
        assertTrue(loaded.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == ModelAssemblyDiagnostic.Code.MODELS_INVALID));
    }

    @Test
    void providerBaseUrlRejectsEmbeddedCredentialsAndQueryValues() {
        var listed = definition("local", "one");
        assertThrows(IllegalArgumentException.class, () -> new ProviderDefinition(
                listed.providerId(), listed.name(), listed.api(),
                URI.create("https://user:secret@example.test/v1"), listed.compatibility(),
                listed.apiKeyEnvironmentVariable(), false, listed.models()));
        assertThrows(IllegalArgumentException.class, () -> new ProviderDefinition(
                listed.providerId(), listed.name(), listed.api(),
                URI.create("https://example.test/v1?key=secret"), listed.compatibility(),
                listed.apiKeyEnvironmentVariable(), false, listed.models()));
    }

    @Test
    void credentialPrecedenceIsLazyAndNeverReportsSecretValues() throws Exception {
        var auth = directory.resolve("auth.json");
        Files.writeString(auth, "broken and must not be read");
        var definition = definition("local", "one");
        var sdkSecret = "sdk-secret-value";
        var environmentReads = new AtomicInteger();
        var resolution = CredentialResolver.resolve(
                List.of(definition),
                new CredentialResolver.Options(
                        Map.of("local", OpenAiCredentials.apiKey(sdkSecret)),
                        true,
                        name -> {
                            environmentReads.incrementAndGet();
                            return "environment-secret-value";
                        },
                        Optional.of(auth),
                        MAPPER));

        assertEquals(CredentialResolver.CredentialSource.SDK,
                resolution.credentials().get("local").source());
        assertEquals(0, environmentReads.get());
        assertTrue(resolution.diagnostics().isEmpty());
        assertFalse(resolution.toString().contains(sdkSecret));
        assertFalse(resolution.credentials().toString().contains(sdkSecret));
    }

    @Test
    void configuredEnvironmentVariableWinsOverTheCredentialFile() throws Exception {
        var auth = directory.resolve("auth.json");
        Files.writeString(auth, "broken and must not be read");
        var definition = definition("local", "one");
        var resolution = CredentialResolver.resolve(
                List.of(definition),
                new CredentialResolver.Options(
                        Map.of(), true,
                        name -> "LOCAL_KEY".equals(name) ? "environment-secret" : null,
                        Optional.of(auth), MAPPER));

        assertEquals(CredentialResolver.CredentialSource.ENVIRONMENT,
                resolution.credentials().get("local").source());
        assertTrue(resolution.diagnostics().isEmpty());
    }

    @Test
    void blankEnvironmentValueIsAnErrorAndDoesNotFallBackToFile() throws Exception {
        var auth = directory.resolve("auth.json");
        Files.writeString(auth, """
                {"providers":{"local":{"type":"api_key","key":"file-secret"}}}
                """);
        makePrivate(auth);
        var definition = definition("local", "one");

        var resolution = CredentialResolver.resolve(
                List.of(definition),
                new CredentialResolver.Options(
                        Map.of(), true, name -> "   ", Optional.of(auth), MAPPER));

        assertFalse(resolution.credentials().containsKey("local"));
        assertTrue(resolution.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == ModelAssemblyDiagnostic.Code.CREDENTIAL_INVALID));
        assertFalse(resolution.toString().contains("file-secret"));
    }

    @Test
    void invalidCredentialFileDiagnosticsNeverIncludeFileValues() throws Exception {
        var auth = directory.resolve("auth.json");
        var secret = "credential-value-that-must-not-leak";
        Files.writeString(auth, """
                {"providers":{"local":{"type":"api_key","key":"%s","extra":true}}}
                """.formatted(secret));
        makePrivate(auth);

        var resolution = CredentialResolver.resolve(
                List.of(definition("local", "one")),
                CredentialResolver.Options.filesOnly(auth, MAPPER));

        assertTrue(resolution.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == ModelAssemblyDiagnostic.Code.CREDENTIAL_INVALID));
        assertFalse(resolution.toString().contains(secret));
    }

    @Test
    void credentialFileRejectsTrailingJsonWithoutReportingSecrets() throws Exception {
        var auth = directory.resolve("trailing-auth.json");
        String secret = "trailing-credential-secret";
        Files.writeString(auth, """
                {"providers":{"local":{"type":"api_key","key":"%s"}}}
                trailing-garbage
                """.formatted(secret));
        makePrivate(auth);

        var resolution = CredentialResolver.resolve(
                List.of(definition("local", "one")),
                CredentialResolver.Options.filesOnly(auth, MAPPER));

        assertFalse(resolution.credentials().containsKey("local"));
        assertTrue(resolution.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == ModelAssemblyDiagnostic.Code.CREDENTIAL_INVALID));
        assertFalse(resolution.toString().contains(secret));
    }

    @Test
    void ownedRuntimeClosesItsConcreteHttpClient() throws Exception {
        var definition = definition("local", "one");
        var credentials = CredentialResolver.resolve(
                List.of(definition),
                new CredentialResolver.Options(
                        Map.of("local", OpenAiCredentials.apiKey("sdk-secret")),
                        false, ignored -> null, Optional.empty(), MAPPER));
        var captured = new AtomicReference<HttpClient>();
        var runtime = ModelRuntimeAssembler.owned(
                List.of(definition), credentials, () -> {
                    var client = HttpClient.newHttpClient();
                    captured.set(client);
                    return client;
                });
        var client = captured.get();
        assertFalse(client.isTerminated());

        runtime.ownedResources().orElseThrow().close();
        runtime.ownedResources().orElseThrow().close();

        assertTrue(client.isTerminated());
    }

    @Test
    void assemblyFailureClosesEveryConcreteHttpClientItCreated() throws Exception {
        var definition = definition("duplicate", "one");
        var credentials = CredentialResolver.resolve(
                List.of(definition),
                new CredentialResolver.Options(
                        Map.of("duplicate", OpenAiCredentials.apiKey("sdk-secret")),
                        false, ignored -> null, Optional.empty(), MAPPER));
        var clients = new ArrayList<HttpClient>();

        assertThrows(IllegalArgumentException.class, () -> ModelRuntimeAssembler.owned(
                List.of(definition, definition), credentials, () -> {
                    var client = HttpClient.newHttpClient();
                    clients.add(client);
                    return client;
                }));

        assertEquals(2, clients.size());
        assertTrue(clients.stream().allMatch(HttpClient::isTerminated));
    }

    @Test
    void fileCredentialBuildsARealProviderWithoutNetworkProbe() throws Exception {
        var auth = directory.resolve("auth.json");
        Files.writeString(auth, """
                {"providers":{"local":{"type":"api_key","key":"file-secret"}}}
                """);
        makePrivate(auth);
        var definition = definition("local", "one");
        var credentials = CredentialResolver.resolve(
                List.of(definition), CredentialResolver.Options.filesOnly(auth, MAPPER));

        var runtime = ModelRuntimeAssembler.owned(List.of(definition), credentials);
        try {
            assertEquals(1, runtime.models().providers().size());
            assertEquals(1, runtime.catalog().entries().size());
            assertTrue(runtime.catalog().entries().getFirst().authConfigured());
            var unlisted = new site.pplee.jcode.ai.model.ModelRef(
                    "local", "responses", "unlisted");
            assertTrue(runtime.models().model(unlisted).isEmpty());
            assertFalse(runtime.models().provider("local").orElseThrow().supports(unlisted));
        } finally {
            runtime.ownedResources().orElseThrow().close();
        }
    }

    @Test
    void allowUnlistedChangesSupportWithoutAdvertisingACatalogEntry() throws Exception {
        var auth = directory.resolve("auth.json");
        Files.writeString(auth, """
                {"providers":{"local":{"type":"api_key","key":"file-secret"}}}
                """);
        makePrivate(auth);
        var listed = definition("local", "one");
        var definition = new ProviderDefinition(
                listed.providerId(), listed.name(), listed.api(), listed.baseUrl(),
                listed.compatibility(), listed.apiKeyEnvironmentVariable(), true, listed.models());
        var credentials = CredentialResolver.resolve(
                List.of(definition), CredentialResolver.Options.filesOnly(auth, MAPPER));
        var runtime = ModelRuntimeAssembler.owned(List.of(definition), credentials);
        try {
            var unlisted = new site.pplee.jcode.ai.model.ModelRef(
                    "local", "responses", "unlisted");
            assertTrue(runtime.models().model(unlisted).isEmpty());
            assertTrue(runtime.models().provider("local").orElseThrow().supports(unlisted));
            assertEquals(1, runtime.catalog().entries().size());
        } finally {
            runtime.ownedResources().orElseThrow().close();
        }
    }

    private static ProviderDefinition definition(String providerId, String modelId) {
        return new ProviderDefinition(
                providerId,
                providerId,
                "responses",
                URI.create("http://127.0.0.1:8080/v1"),
                OpenAiResponsesCompatibility.openaiNoSession(),
                Optional.of("LOCAL_KEY"),
                false,
                List.of(new ProviderDefinition.DefinedModel(
                        modelId, modelId, OpenAiModelCapabilities.noReasoning(),
                        ModelProfile.empty())));
    }

    private static void makePrivate(Path path) throws Exception {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // The test host supplies the explicit directory on non-POSIX systems.
        }
    }
}
