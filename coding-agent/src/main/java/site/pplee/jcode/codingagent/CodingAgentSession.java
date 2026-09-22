package site.pplee.jcode.codingagent;

import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.ai.provider.Models;
import site.pplee.jcode.ai.tool.ToolSpec;
import site.pplee.jcode.agentcore.Agent;
import site.pplee.jcode.agentcore.AgentConfig;
import site.pplee.jcode.agentcore.AgentContext;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.message.ContextTransformer;
import site.pplee.jcode.agentcore.message.MessageProjector;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.tool.AfterToolCall;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;
import site.pplee.jcode.agentcore.turn.PrepareNextTurn;
import site.pplee.jcode.agentcore.turn.ShouldStopAfterTurn;
import site.pplee.jcode.codingagent.context.ProjectContextConfig;
import site.pplee.jcode.codingagent.context.ProjectContextDiagnostic;
import site.pplee.jcode.codingagent.context.ProjectContextLoader;
import site.pplee.jcode.codingagent.context.ProjectContextSnapshot;
import site.pplee.jcode.codingagent.event.CodingAgentEvent;
import site.pplee.jcode.codingagent.event.CodingAgentEventSink;
import site.pplee.jcode.codingagent.internal.SnapshotMapper;
import site.pplee.jcode.codingagent.model.ModelSelection;
import site.pplee.jcode.codingagent.prompt.SystemPromptBuilder;
import site.pplee.jcode.codingagent.session.LabelEntry;
import site.pplee.jcode.codingagent.session.SessionContextBuilder;
import site.pplee.jcode.codingagent.session.SessionDiagnostic;
import site.pplee.jcode.codingagent.session.SessionHeader;
import site.pplee.jcode.codingagent.session.SessionInfoEntry;
import site.pplee.jcode.codingagent.session.SessionSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Headless product facade for one coding-agent conversation.
 *
 * <p>The session owns its underlying {@link Agent}, serializes run admission
 * and queue acceptance, and exposes only immutable product snapshots.
 */
public final class CodingAgentSession implements AutoCloseable {
    private final Object lifecycleLock = new Object();
    private final Agent agent;
    private final BuiltInTools.ToolSet toolSet;
    private final Path workingDirectory;
    private final Clock clock;
    private final SessionManager sessionManager;
    private final List<SessionDiagnostic> sessionDiagnostics;
    private final Models modelDirectory;
    private final AutoCloseable ownedResource;
    private final AtomicBoolean ownedResourceClosed = new AtomicBoolean();
    private volatile ModelSelection currentSelection;
    private final ExecutorService reloadExecutor;
    // Remains set through callbacks, including callbacks that admit another reload.
    private final ThreadLocal<Boolean> reloadWorker = new ThreadLocal<>();
    private final ProjectContextConfig projectContextConfig;
    private final ContextLoader contextLoader;
    private final List<ToolSpec> toolSpecs;
    private final String customSystemPrompt;
    private final String appendSystemPrompt;
    private ProjectContextSnapshot projectContext;
    private CancellationSource reloadSource;
    private boolean running;
    private boolean reloading;
    private boolean historyOperation;
    private boolean closed;

    public CodingAgentSession(CodingAgentConfig config) {
        this(config, ProjectContextLoader::load, newInMemoryManager(config));
    }

    /** Create a new file-backed session and acquire exclusive ownership of its file. */
    public static CodingAgentSession create(CodingAgentConfig config, Path sessionDirectory)
            throws IOException {
        Objects.requireNonNull(config, "config must not be null");
        var header = new SessionHeader(
                UUID.randomUUID(), config.clock().instant(), config.workingDirectory());
        var manager = SessionManager.createFileBacked(
                header, sessionDirectory, config.clock());
        return constructWithOwnedManager(config, manager);
    }

    /** Open an existing file-backed session and acquire exclusive ownership of its file. */
    public static CodingAgentSession open(CodingAgentConfig config, Path sessionFile)
            throws IOException {
        Objects.requireNonNull(config, "config must not be null");
        var manager = SessionManager.openFileBacked(sessionFile, config.clock());
        return constructWithOwnedManager(config, manager);
    }

    CodingAgentSession(CodingAgentConfig config, ContextLoader contextLoader) {
        this(config, contextLoader, newInMemoryManager(config));
    }

    CodingAgentSession(
            CodingAgentConfig config,
            ContextLoader contextLoader,
            SessionManager sessionManager
    ) {
        this(config, contextLoader, sessionManager, null, null);
    }

    CodingAgentSession(
            CodingAgentConfig config,
            ContextLoader contextLoader,
            SessionManager sessionManager,
            BuiltInTools.ToolSet suppliedToolSet
    ) {
        this(config, contextLoader, sessionManager, suppliedToolSet, null);
    }

    CodingAgentSession(
            CodingAgentConfig config,
            ContextLoader contextLoader,
            SessionManager sessionManager,
            BuiltInTools.ToolSet suppliedToolSet,
            AutoCloseable ownedResource
    ) {
        this(config, contextLoader, sessionManager, suppliedToolSet, ownedResource, null);
    }

    CodingAgentSession(
            CodingAgentConfig config,
            ContextLoader contextLoader,
            SessionManager sessionManager,
            BuiltInTools.ToolSet suppliedToolSet,
            AutoCloseable ownedResource,
            ModelSelection initialSelection
    ) {
        Objects.requireNonNull(config, "config must not be null");
        this.contextLoader = Objects.requireNonNull(contextLoader, "contextLoader must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.workingDirectory = config.workingDirectory();
        this.sessionDiagnostics = buildSessionDiagnostics(sessionManager, workingDirectory);
        this.clock = config.clock();
        this.modelDirectory = config.modelClient() instanceof Models models ? models : null;
        this.ownedResource = ownedResource;
        this.currentSelection = initialSelection == null
                ? runtimeSelection(config.model(), config.thinkingLevel())
                : requireMatchingSelection(initialSelection, config);
        this.projectContextConfig = config.projectContext();
        this.customSystemPrompt = config.customSystemPrompt();
        this.appendSystemPrompt = config.appendSystemPrompt();

        var initialCancellation = new CancellationSource();
        this.projectContext = projectContextConfig.enabled()
                ? contextLoader.load(workingDirectory, projectContextConfig, 1, initialCancellation.signal())
                : ProjectContextSnapshot.disabled(workingDirectory);
        var createdToolSet = suppliedToolSet == null
                ? BuiltInTools.create(workingDirectory, config.tools())
                : suppliedToolSet;
        Agent createdAgent;
        try {
            var tools = createdToolSet.tools();
            this.toolSpecs = tools.stream().map(tool -> tool.spec()).toList();
            String systemPrompt = SystemPromptBuilder.build(
                    workingDirectory,
                    toolSpecs,
                    customSystemPrompt,
                    appendSystemPrompt,
                    projectContext.files());
            var restored = SessionContextBuilder.build(sessionManager.snapshot());
            var context = new AgentContext(systemPrompt, restored.messages(), tools);
            var eventSink = config.eventSink();
            createdAgent = new Agent(new AgentConfig(
                    context,
                    config.model(),
                    config.modelClient(),
                    config.objectMapper(),
                    ContextTransformer.identity(),
                    MessageProjector.standard(),
                    ToolExecutionMode.PARALLEL,
                    CodingToolPolicyAdapter.adapt(config.tools().policy(), workingDirectory),
                    AfterToolCall.noop(),
                    event -> emitProductEvent(event, eventSink),
                    config.steeringMode(),
                    config.followUpMode(),
                    config.thinkingLevel(),
                    PrepareNextTurn.noop(),
                    ShouldStopAfterTurn.never(),
                    config.requestOptions()));
        } catch (RuntimeException | Error failure) {
            try {
                createdToolSet.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            if (ownedResource != null && ownedResourceClosed.compareAndSet(false, true)) {
                try {
                    ownedResource.close();
                } catch (Exception closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
        this.toolSet = createdToolSet;
        this.agent = createdAgent;
        this.reloadExecutor = projectContextConfig.enabled()
                ? Executors.newVirtualThreadPerTaskExecutor() : null;
    }

    /**
     * Start a run with one text user message; concurrent runs fail fast.
     * Cancelling the returned observation stage does not cancel the accepted run.
     */
    public CompletionStage<CodingAgentRunResult> prompt(String text) {
        Message.User message = userMessage(text);
        return startRun(() -> agent.prompt(message));
    }

    /**
     * Continue from a user or tool-result leaf without synthesizing another user message.
     * Cancelling the returned observation stage does not cancel the accepted run.
     */
    public CompletionStage<CodingAgentRunResult> continueRun() {
        return startRun(agent::continueRun);
    }

    private CompletionStage<CodingAgentRunResult> startRun(
            java.util.function.Supplier<CompletionStage<site.pplee.jcode.agentcore.LoopResult>> starter
    ) {
        CompletionStage<site.pplee.jcode.agentcore.LoopResult> runtimeStage;
        synchronized (lifecycleLock) {
            ensureOpen();
            if (running || reloading || historyOperation) {
                throw new IllegalStateException("coding-agent session is busy");
            }
            try {
                sessionManager.requireWritable();
            } catch (IOException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            running = true;
            try {
                runtimeStage = starter.get();
            } catch (RuntimeException | Error failure) {
                running = false;
                throw failure;
            }
        }

        var productStage = new CompletableFuture<CodingAgentRunResult>();
        runtimeStage.whenComplete((runtimeResult, failure) ->
                finishRun(runtimeResult, failure, productStage));
        return productStage.copy();
    }

    private void finishRun(
            site.pplee.jcode.agentcore.LoopResult runtimeResult,
            Throwable failure,
            CompletableFuture<CodingAgentRunResult> productStage
    ) {
        CodingAgentRunResult productResult = null;
        Throwable completionFailure = failure;
        if (completionFailure == null) {
            try {
                productResult = SnapshotMapper.runResult(runtimeResult);
            } catch (RuntimeException | Error mappingFailure) {
                completionFailure = mappingFailure;
            }
        }

        boolean closeManager;
        synchronized (lifecycleLock) {
            if (failure != null && !closed) {
                try {
                    agent.replaceMessages(
                            SessionContextBuilder.build(sessionManager.snapshot()).messages());
                } catch (RuntimeException | Error alignmentFailure) {
                    failure.addSuppressed(alignmentFailure);
                }
            }
            running = false;
            closeManager = closed;
        }
        if (closeManager) {
            completionFailure = closeManager(completionFailure);
        }
        if (completionFailure != null) {
            productStage.completeExceptionally(completionFailure);
        } else {
            productStage.complete(productResult);
        }
    }

    /** Queue a steering message while this session run is admitted. */
    public void steer(String text) {
        Message.User message = userMessage(text);
        synchronized (lifecycleLock) {
            ensureMessageQueueOpen();
            agent.steer(message);
        }
    }

    /** Queue a follow-up message while this session run is admitted. */
    public void followUp(String text) {
        Message.User message = userMessage(text);
        synchronized (lifecycleLock) {
            ensureMessageQueueOpen();
            agent.followUp(message);
        }
    }

    /** Request cancellation of the current run or reload operation, if any. */
    public void abort() {
        synchronized (lifecycleLock) {
            if (running) {
                agent.abort();
            } else if (reloading && reloadSource != null) {
                reloadSource.cancel();
            }
        }
    }

    /** Return the last successfully applied project instruction snapshot. */
    public ProjectContextSnapshot projectContext() {
        synchronized (lifecycleLock) {
            return projectContext;
        }
    }

    /** Return diagnostics from the last successfully applied project instruction snapshot. */
    public List<ProjectContextDiagnostic> projectContextDiagnostics() {
        synchronized (lifecycleLock) {
            return projectContext.diagnostics();
        }
    }

    /** True while an admitted project instruction reload is in progress. */
    public boolean isReloading() {
        synchronized (lifecycleLock) {
            return reloading;
        }
    }

    /**
     * Reload project instructions while idle and atomically apply the rebuilt prompt.
     *
     * <p>A failed or cancelled reload leaves the prior snapshot and prompt unchanged.
     * Admission is released before completion callbacks run. The returned stage is
     * an observation copy; use {@link #abort()} to cancel the actual operation.
     */
    public CompletionStage<ProjectContextSnapshot> reloadProjectContext() {
        var operation = new CompletableFuture<ProjectContextSnapshot>();
        CancellationSource source;
        long revision;
        synchronized (lifecycleLock) {
            ensureOpen();
            if (running || reloading || historyOperation) {
                throw new IllegalStateException("coding-agent session is busy");
            }
            if (!projectContextConfig.enabled()) {
                throw new IllegalStateException("project context discovery is disabled");
            }
            reloading = true;
            source = new CancellationSource();
            reloadSource = source;
            revision = projectContext.revision() + 1;
        }

        try {
            Objects.requireNonNull(reloadExecutor, "reload executor must exist when project context is enabled")
                    .execute(() -> runReload(source, revision, operation));
        } catch (RuntimeException failure) {
            operation.completeExceptionally(releaseFailedReload(source, failure));
        }
        return operation.copy();
    }

    /** Finish loading and release admission before invoking any completion callbacks. */
    private void runReload(
            CancellationSource source,
            long revision,
            CompletableFuture<ProjectContextSnapshot> operation
    ) {
        reloadWorker.set(true);
        try {
            ProjectContextSnapshot completed;
            try {
                var candidate = contextLoader.load(
                        workingDirectory, projectContextConfig, revision, source.signal());
                source.signal().throwIfCancelled();
                String prompt = SystemPromptBuilder.build(
                        workingDirectory, toolSpecs, customSystemPrompt, appendSystemPrompt, candidate.files());
                synchronized (lifecycleLock) {
                    source.signal().throwIfCancelled();
                    ensureOpen();
                    if (!reloading || reloadSource != source) {
                        throw new IllegalStateException("project context reload is no longer active");
                    }
                    agent.updateSystemPrompt(prompt);
                    projectContext = candidate;
                    completed = candidate;
                    releaseReload(source);
                }
            } catch (Throwable failure) {
                operation.completeExceptionally(releaseFailedReload(source, failure));
                return;
            }
            operation.complete(completed);
        } finally {
            reloadWorker.remove();
        }
    }

    /** Preserve cancellation when it wins the failure/cleanup boundary, including close interruptions. */
    private Throwable releaseFailedReload(CancellationSource source, Throwable failure) {
        synchronized (lifecycleLock) {
            releaseReload(source);
            return source.signal().isCancelled()
                    ? new CancellationException("project context reload cancelled") : failure;
        }
    }

    /** Release this operation's admission; the caller must hold the lifecycle lock. */
    private void releaseReload(CancellationSource source) {
        if (reloadSource == source) {
            reloadSource = null;
            reloading = false;
        }
    }

    /** Return an immutable product snapshot derived from the current runtime state. */
    public CodingAgentState state() {
        synchronized (lifecycleLock) {
            var runtime = agent.state();
            return new CodingAgentState(
                    running,
                    runtime.streamingMessage(),
                    runtime.pendingToolCalls(),
                    runtime.errorMessage());
        }
    }

    /** True from successful prompt admission through product-stage finalization. */
    public boolean isRunning() {
        synchronized (lifecycleLock) {
            return running;
        }
    }

    /** Normalized absolute working directory used by tools and the prompt. */
    public Path workingDirectory() {
        return workingDirectory;
    }

    /** Current model and thinking pair used by the next admitted run. */
    public ModelSelection modelSelection() {
        return currentSelection;
    }

    /** Atomically update the model and thinking level while the session is idle. */
    public ModelSelection setModel(ModelRef model, ThinkingLevel thinkingLevel) throws IOException {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(thinkingLevel, "thinkingLevel must not be null");
        return updateModelSelection(model, thinkingLevel);
    }

    /** Update only the thinking level while retaining the current model. */
    public ModelSelection setThinkingLevel(ThinkingLevel thinkingLevel) throws IOException {
        Objects.requireNonNull(thinkingLevel, "thinkingLevel must not be null");
        return updateModelSelection(null, thinkingLevel);
    }

    private ModelSelection updateModelSelection(
            ModelRef requestedModel,
            ThinkingLevel thinkingLevel
    ) throws IOException {
        admitHistoryOperation();
        Throwable failure = null;
        ModelSelection result = null;
        try {
            sessionManager.requireWritable();
            var previous = currentSelection;
            var targetModel = requestedModel == null ? previous.selected() : requestedModel;
            validateModel(targetModel);
            synchronized (lifecycleLock) {
                ensureOpen();
                agent.updateModel(targetModel, thinkingLevel);
                var updated = runtimeSelection(targetModel, thinkingLevel);
                currentSelection = updated;
                result = updated;
            }
        } catch (IOException | RuntimeException | Error operationFailure) {
            failure = operationFailure;
        }
        failure = finishHistoryOperation(failure);
        if (failure instanceof IOException ioFailure) {
            throw ioFailure;
        }
        rethrowHistoryFailure(failure);
        return result;
    }

    private void validateModel(ModelRef target) {
        if (modelDirectory == null) {
            return;
        }
        var provider = modelDirectory.provider(target.provider()).orElseThrow(() ->
                new IllegalArgumentException("model provider is not assembled: " + target.provider()));
        if (!provider.supports(target)) {
            throw new IllegalArgumentException(
                    "model is not supported: " + target.provider() + "/" + target.api()
                            + "/" + target.modelId());
        }
        if (!provider.auth().isConfigured()) {
            throw new IllegalArgumentException(
                    "model provider authentication is not configured: " + target.provider());
        }
    }

    private static ModelSelection runtimeSelection(
            ModelRef model,
            ThinkingLevel thinkingLevel
    ) {
        return new ModelSelection(
                Optional.of(model),
                Optional.empty(),
                model,
                thinkingLevel,
                ModelSelection.Source.RUNTIME,
                List.of());
    }

    private static ModelSelection requireMatchingSelection(
            ModelSelection selection,
            CodingAgentConfig config
    ) {
        Objects.requireNonNull(selection, "initialSelection must not be null");
        if (!selection.selected().equals(config.model())
                || selection.thinkingLevel() != config.thinkingLevel()) {
            throw new IllegalArgumentException(
                    "initial selection must match the session model and thinking level");
        }
        return selection;
    }

    /** Return an immutable point-in-time view of the accepted product history. */
    public SessionSnapshot history() {
        return sessionManager.snapshot();
    }

    /**
     * Select an existing history node and replace the idle runtime transcript
     * with its root-to-node message path. Pending queues remain owned by this object.
     */
    public void branch(String entryId) {
        Objects.requireNonNull(entryId, "entryId must not be null");
        admitHistoryOperation();
        Throwable failure = null;
        try {
            var messages = SessionContextBuilder.build(sessionManager.snapshot(), entryId).messages();
            synchronized (lifecycleLock) {
                ensureOpen();
                agent.replaceMessages(messages);
                sessionManager.branch(entryId);
            }
        } catch (RuntimeException | Error operationFailure) {
            failure = operationFailure;
        }
        rethrowHistoryFailure(finishHistoryOperation(failure));
    }

    /**
     * Select the position before every root and clear the idle runtime transcript.
     * Existing entries and pending queues are retained.
     */
    public void resetLeaf() {
        admitHistoryOperation();
        Throwable failure = null;
        try {
            synchronized (lifecycleLock) {
                ensureOpen();
                agent.replaceMessages(List.of());
                sessionManager.resetLeaf();
            }
        } catch (RuntimeException | Error operationFailure) {
            failure = operationFailure;
        }
        rethrowHistoryFailure(finishHistoryOperation(failure));
    }

    /** Append a global display-name change while idle; null clears the name. */
    public SessionInfoEntry setName(String name) throws IOException {
        admitHistoryOperation();
        SessionInfoEntry result = null;
        Throwable failure = null;
        try {
            result = sessionManager.setName(name);
        } catch (IOException | RuntimeException | Error operationFailure) {
            failure = operationFailure;
        }
        failure = finishHistoryOperation(failure);
        if (failure instanceof IOException ioFailure) {
            throw ioFailure;
        }
        rethrowHistoryFailure(failure);
        return result;
    }

    /** Append a display-label change for an existing entry; null clears the label. */
    public LabelEntry setLabel(String entryId, String label) throws IOException {
        Objects.requireNonNull(entryId, "entryId must not be null");
        admitHistoryOperation();
        LabelEntry result = null;
        Throwable failure = null;
        try {
            result = sessionManager.setLabel(entryId, label);
        } catch (IOException | RuntimeException | Error operationFailure) {
            failure = operationFailure;
        }
        failure = finishHistoryOperation(failure);
        if (failure instanceof IOException ioFailure) {
            throw ioFailure;
        }
        rethrowHistoryFailure(failure);
        return result;
    }

    /** Return the owned JSONL path, or empty for the default in-memory mode. */
    public Optional<Path> sessionFile() {
        return Optional.ofNullable(sessionManager.filePath());
    }

    /** Return immutable diagnostics produced while opening this session. */
    public List<SessionDiagnostic> sessionDiagnostics() {
        return sessionDiagnostics;
    }

    /**
     * Close owned resources and request cancellation of any active reload.
     *
     * <p>Waiting for loader shutdown is bounded to two seconds and skipped on a
     * loader worker. Uninterruptible file I/O keeps its stage pending and its
     * reload admission occupied until actual loading and cleanup have finished.
     * An active run retains its Session writer until core emits its terminal
     * messages and the run completion callback settles. An accepted history
     * append likewise retains the writer until its file and memory commit finishes.
     */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            if (reloadSource != null) {
                reloadSource.cancel();
            }
        }
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        try {
            agent.close();
        } catch (RuntimeException failure) {
            runtimeFailure = failure;
        } catch (Error failure) {
            errorFailure = failure;
        }
        if (reloadExecutor != null) {
            reloadExecutor.shutdownNow();
            if (!Boolean.TRUE.equals(reloadWorker.get())) {
                try {
                    reloadExecutor.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    if (runtimeFailure == null && errorFailure == null) {
                        runtimeFailure = new IllegalStateException(
                                "interrupted while closing project context loader", failure);
                    }
                }
            }
        }
        // An uninterruptible load retains admission and settles only when its worker actually cleans up.
        try {
            toolSet.close();
        } catch (RuntimeException failure) {
            if (runtimeFailure != null) {
                runtimeFailure.addSuppressed(failure);
            } else if (errorFailure != null) {
                errorFailure.addSuppressed(failure);
            } else {
                runtimeFailure = failure;
            }
        } catch (Error failure) {
            if (runtimeFailure != null) {
                runtimeFailure.addSuppressed(failure);
            } else if (errorFailure != null) {
                errorFailure.addSuppressed(failure);
            } else {
                errorFailure = failure;
            }
        }
        boolean closeManagerNow;
        synchronized (lifecycleLock) {
            closeManagerNow = !running && !historyOperation;
        }
        if (closeManagerNow) {
            Throwable existingFailure = runtimeFailure != null ? runtimeFailure : errorFailure;
            Throwable closeFailure = closeManager(existingFailure);
            if (existingFailure == null && closeFailure != null) {
                if (closeFailure instanceof Error error) {
                    errorFailure = error;
                } else if (closeFailure instanceof RuntimeException runtime) {
                    runtimeFailure = runtime;
                } else {
                    runtimeFailure = new IllegalStateException(
                            "unexpected checked session close failure", closeFailure);
                }
            }
        }
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
        if (errorFailure != null) {
            throw errorFailure;
        }
    }

    private CompletionStage<Void> emitProductEvent(
            AgentEvent event,
            CodingAgentEventSink eventSink
    ) {
        if (event instanceof AgentEvent.MessageCompleted completed) {
            var message = SnapshotMapper.agentMessage(completed.message());
            if (!(message instanceof StandardAgentMessage standard)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "phase-four sessions only persist standard agent messages"));
            }
            try {
                var selection = currentSelection;
                sessionManager.appendCompletedMessage(
                        standard, selection.selected(), selection.thinkingLevel());
            } catch (IOException | RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        CodingAgentEvent productEvent = event instanceof AgentEvent.AgentCompleted completed
                ? new CodingAgentEvent.RunCompleted(SnapshotMapper.runResult(completed.result()))
                : new CodingAgentEvent.RuntimeEvent(event);
        var stage = eventSink.emit(productEvent);
        if (stage == null) {
            throw new IllegalStateException("coding event sink returned null stage");
        }
        return stage;
    }

    private static List<SessionDiagnostic> buildSessionDiagnostics(
            SessionManager manager,
            Path workingDirectory
    ) {
        var diagnostics = new java.util.ArrayList<SessionDiagnostic>();
        var headerCwd = manager.snapshot().header().cwd();
        if (!headerCwd.equals(workingDirectory)) {
            diagnostics.add(new SessionDiagnostic.WorkingDirectoryMismatch(
                    headerCwd, workingDirectory));
        }
        var recovery = manager.recovery();
        var sessionFile = manager.filePath();
        if (recovery != null && sessionFile != null) {
            diagnostics.add(new SessionDiagnostic.RecoveredTail(
                    sessionFile,
                    recovery.lineNumber(),
                    recovery.byteOffset(),
                    recovery.discardedBytes(),
                    SessionDiagnostic.Reason.valueOf(recovery.reason().name())));
        }
        return List.copyOf(diagnostics);
    }

    private static SessionManager newInMemoryManager(CodingAgentConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return new SessionManager(new SessionHeader(
                UUID.randomUUID(), config.clock().instant(), config.workingDirectory()), config.clock());
    }

    private static CodingAgentSession constructWithOwnedManager(
            CodingAgentConfig config,
            SessionManager manager
    ) throws IOException {
        try {
            return new CodingAgentSession(config, ProjectContextLoader::load, manager);
        } catch (RuntimeException | Error failure) {
            try {
                manager.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private void admitHistoryOperation() {
        synchronized (lifecycleLock) {
            ensureOpen();
            if (running || reloading || historyOperation) {
                throw new IllegalStateException("coding-agent session is busy");
            }
            historyOperation = true;
        }
    }

    private Throwable finishHistoryOperation(Throwable existingFailure) {
        boolean closeManager;
        synchronized (lifecycleLock) {
            historyOperation = false;
            closeManager = closed;
        }
        return closeManager ? closeManager(existingFailure) : existingFailure;
    }

    private static void rethrowHistoryFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }
        if (failure != null) {
            throw new IllegalStateException("unexpected checked history-operation failure", failure);
        }
    }

    private Throwable closeManager(Throwable existingFailure) {
        Throwable failure = existingFailure;
        try {
            sessionManager.close();
        } catch (IOException closeFailure) {
            if (failure == null) {
                failure = new UncheckedIOException(closeFailure);
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (ownedResource != null && ownedResourceClosed.compareAndSet(false, true)) {
            try {
                ownedResource.close();
            } catch (Exception closeFailure) {
                if (failure == null) {
                    failure = new IllegalStateException(
                            "could not close session-owned model resources", closeFailure);
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        return failure;
    }

    @FunctionalInterface
    interface ContextLoader {
        ProjectContextSnapshot load(
                Path workingDirectory,
                ProjectContextConfig config,
                long revision,
                CancellationSignal cancellation
        );
    }

    private Message.User userMessage(String text) {
        Objects.requireNonNull(text, "text must not be null");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        return new Message.User(List.of(new Content.Text(text)), clock.instant());
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("coding-agent session is closed");
        }
    }

    private void ensureMessageQueueOpen() {
        ensureOpen();
        if (!running) {
            throw new IllegalStateException("coding-agent session is not running");
        }
    }
}
