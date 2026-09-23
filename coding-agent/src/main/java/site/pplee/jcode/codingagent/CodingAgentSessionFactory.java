package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.context.ProjectContextLoader;
import site.pplee.jcode.codingagent.model.CredentialResolver;
import site.pplee.jcode.codingagent.model.ModelAssemblyDiagnostic;
import site.pplee.jcode.codingagent.model.ModelRuntime;
import site.pplee.jcode.codingagent.model.ModelRuntimeAssembler;
import site.pplee.jcode.codingagent.model.ModelSelection;
import site.pplee.jcode.codingagent.model.ModelSelectionException;
import site.pplee.jcode.codingagent.model.ModelSelector;
import site.pplee.jcode.codingagent.model.ProviderDefinitions;
import site.pplee.jcode.codingagent.session.SessionHeader;
import site.pplee.jcode.codingagent.settings.CodingAgentSettings;
import site.pplee.jcode.codingagent.settings.SettingsLoadRequest;
import site.pplee.jcode.codingagent.settings.SettingsLoadResult;
import site.pplee.jcode.codingagent.settings.SettingsLoader;
import site.pplee.jcode.codingagent.settings.SettingsOverrides;
import site.pplee.jcode.codingagent.tool.CodingToolConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Settings-driven, one-shot assembly for in-memory, new, or restored sessions. */
public final class CodingAgentSessionFactory {
    private CodingAgentSessionFactory() {
    }

    public static SessionCreationResult inMemory(CodingAgentSessionOptions options)
            throws IOException, SessionAssemblyException {
        return assemble(options, null, null);
    }

    public static SessionCreationResult create(
            CodingAgentSessionOptions options,
            Path sessionDirectory
    ) throws IOException, SessionAssemblyException {
        if (sessionDirectory == null) {
            throw new NullPointerException("sessionDirectory must not be null");
        }
        return assemble(options, sessionDirectory, null);
    }

    public static SessionCreationResult open(
            CodingAgentSessionOptions options,
            Path sessionFile
    ) throws IOException, SessionAssemblyException {
        if (sessionFile == null) {
            throw new NullPointerException("sessionFile must not be null");
        }
        return assemble(options, null, sessionFile);
    }

    private static SessionCreationResult assemble(
            CodingAgentSessionOptions options,
            Path createDirectory,
            Path openFile
    ) throws IOException, SessionAssemblyException {
        if (options == null) {
            throw new NullPointerException("options must not be null");
        }
        var settings = SettingsLoader.load(new SettingsLoadRequest(
                options.workingDirectory(),
                options.userConfigDirectory(),
                options.projectTrust(),
                effectiveOverrides(options),
                options.objectMapper()));
        ModelRuntime runtime = buildRuntime(options, settings);
        SessionManager manager = null;
        BuiltInTools.ToolSet preparedTools = null;
        try {
            if (openFile != null) {
                manager = SessionManager.openFileBacked(openFile, options.clock());
            }
            ModelSelection selection;
            try {
                selection = manager == null
                        ? ModelSelector.selectNew(settings.settings(), runtime.models())
                        : ModelSelector.selectRestored(
                                settings.settings(), runtime.models(),
                                manager.currentModel(), manager.currentThinkingLevel());
            } catch (ModelSelectionException failure) {
                var diagnostics = new ArrayList<>(runtime.diagnostics());
                diagnostics.addAll(failure.diagnostics());
                throw new SessionAssemblyException(
                        failure.getMessage(), settings.diagnostics(), diagnostics);
            }

            var config = createConfig(options, settings, runtime, selection);
            preparedTools = BuiltInTools.create(options.workingDirectory(), config.tools());
            if (manager == null) {
                var header = new SessionHeader(
                        UUID.randomUUID(), options.clock().instant(), options.workingDirectory());
                manager = createDirectory == null
                        ? new SessionManager(header, options.clock())
                        : SessionManager.createFileBacked(
                                header, createDirectory, options.clock());
            }
            var catalog = runtime.catalog();
            var diagnostics = new ArrayList<>(runtime.diagnostics());
            diagnostics.addAll(selection.diagnostics());
            var ownedResource = runtime.ownedResources().orElse(null);
            var session = new CodingAgentSession(
                    config, ProjectContextLoader::load, manager,
                    preparedTools, ownedResource, selection);
            preparedTools = null;
            manager = null;
            runtime = new ModelRuntime(
                    runtime.models(), runtime.profiles(), runtime.diagnostics(), Optional.empty());
            return new SessionCreationResult(
                    session,
                    settings,
                    selection,
                    catalog,
                    diagnostics);
        } catch (IOException | SessionAssemblyException | RuntimeException | Error failure) {
            closeTools(preparedTools, failure);
            closeManager(manager, failure);
            closeRuntime(runtime, failure);
            throw failure;
        }
    }

    private static ModelRuntime buildRuntime(
            CodingAgentSessionOptions options,
            SettingsLoadResult settings
    ) throws SessionAssemblyException {
        if (options.borrowedModels().isPresent()) {
            return ModelRuntimeAssembler.borrowed(
                    options.borrowedModels().orElseThrow(), options.borrowedProfiles());
        }

        List<site.pplee.jcode.codingagent.model.ProviderDefinition> fileDefinitions = List.of();
        var diagnostics = new ArrayList<ModelAssemblyDiagnostic>();
        if (options.userConfigDirectory().isPresent()) {
            var loaded = ProviderDefinitions.load(
                    options.userConfigDirectory().orElseThrow().resolve("models.json"),
                    options.objectMapper());
            diagnostics.addAll(loaded.diagnostics());
            if (!loaded.valid()) {
                throw new SessionAssemblyException(
                        "provider definitions could not be loaded",
                        settings.diagnostics(), diagnostics);
            }
            fileDefinitions = loaded.definitions();
        }
        var definitions = ProviderDefinitions.merge(
                fileDefinitions, options.providerDefinitions());
        var credentialOptions = new CredentialResolver.Options(
                options.credentials(),
                options.environmentEnabled(),
                options.environment(),
                options.userConfigDirectory().map(path -> path.resolve("auth.json")),
                options.objectMapper());
        var credentials = CredentialResolver.resolve(definitions, credentialOptions);
        var runtime = ModelRuntimeAssembler.owned(definitions, credentials);
        if (diagnostics.isEmpty()) {
            return runtime;
        }
        var combined = new ArrayList<>(diagnostics);
        combined.addAll(runtime.diagnostics());
        return new ModelRuntime(
                runtime.models(), runtime.profiles(), combined, runtime.ownedResources());
    }

    private static SettingsOverrides effectiveOverrides(CodingAgentSessionOptions options) {
        var overrides = options.settingsOverrides();
        if (!options.toolsExplicitlyConfigured()) {
            return overrides;
        }
        var settings = overrides.settings();
        var enabledTools = options.tools().enabledTools().stream().sorted().toList();
        var withExplicitTools = new CodingAgentSettings(
                settings.defaultModel(),
                settings.defaultThinkingLevel(),
                Optional.of(enabledTools),
                settings.steeringMode(),
                settings.followUpMode(),
                settings.maxOutputTokens(),
                settings.temperature(),
                settings.compaction());
        return new SettingsOverrides(withExplicitTools, overrides.requestOptions());
    }

    private static CodingAgentConfig createConfig(
            CodingAgentSessionOptions options,
            SettingsLoadResult settings,
            ModelRuntime runtime,
            ModelSelection selection
    ) {
        var baseTools = options.tools();
        var tools = new CodingToolConfig(
                settings.settings().defaultTools(),
                baseTools.bash(),
                baseTools.search(),
                baseTools.policy());
        return new CodingAgentConfig(
                options.workingDirectory(),
                selection.selected(),
                runtime.models(),
                options.objectMapper(),
                selection.thinkingLevel(),
                settings.settings().requestOptions(),
                settings.settings().steeringMode(),
                settings.settings().followUpMode(),
                options.customSystemPrompt(),
                options.appendSystemPrompt(),
                options.eventSink(),
                options.clock(),
                tools,
                options.projectContext(),
                settings.settings().compaction(),
                runtime.profiles(),
                options.customization());
    }

    private static void closeTools(BuiltInTools.ToolSet tools, Throwable failure) {
        if (tools == null) {
            return;
        }
        try {
            tools.close();
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeManager(SessionManager manager, Throwable failure) {
        if (manager == null) {
            return;
        }
        try {
            manager.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeRuntime(ModelRuntime runtime, Throwable failure) {
        if (runtime == null || runtime.ownedResources().isEmpty()) {
            return;
        }
        try {
            runtime.ownedResources().orElseThrow().close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
