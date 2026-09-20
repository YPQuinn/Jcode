package site.pplee.jcode.codingagent;

import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.tool.ToolSpec;
import site.pplee.jcode.agentcore.Agent;
import site.pplee.jcode.agentcore.AgentConfig;
import site.pplee.jcode.agentcore.AgentContext;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.message.ContextTransformer;
import site.pplee.jcode.agentcore.message.MessageProjector;
import site.pplee.jcode.agentcore.tool.AfterToolCall;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;
import site.pplee.jcode.agentcore.turn.PrepareNextTurn;
import site.pplee.jcode.agentcore.turn.ShouldStopAfterTurn;
import site.pplee.jcode.codingagent.context.ProjectContextConfig;
import site.pplee.jcode.codingagent.context.ProjectContextDiagnostic;
import site.pplee.jcode.codingagent.context.ProjectContextLoader;
import site.pplee.jcode.codingagent.context.ProjectContextSnapshot;
import site.pplee.jcode.codingagent.event.CodingAgentEvent;
import site.pplee.jcode.codingagent.internal.SnapshotMapper;
import site.pplee.jcode.codingagent.prompt.SystemPromptBuilder;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private boolean closed;

    public CodingAgentSession(CodingAgentConfig config) {
        this(config, ProjectContextLoader::load);
    }

    CodingAgentSession(CodingAgentConfig config, ContextLoader contextLoader) {
        Objects.requireNonNull(config, "config must not be null");
        this.contextLoader = Objects.requireNonNull(contextLoader, "contextLoader must not be null");
        this.workingDirectory = config.workingDirectory();
        this.clock = config.clock();
        this.projectContextConfig = config.projectContext();
        this.customSystemPrompt = config.customSystemPrompt();
        this.appendSystemPrompt = config.appendSystemPrompt();

        var initialCancellation = new CancellationSource();
        this.projectContext = projectContextConfig.enabled()
                ? contextLoader.load(workingDirectory, projectContextConfig, 1, initialCancellation.signal())
                : ProjectContextSnapshot.disabled(workingDirectory);
        var createdToolSet = BuiltInTools.create(workingDirectory, config.tools());
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
            var context = new AgentContext(systemPrompt, List.of(), tools);
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
                    event -> {
                        CodingAgentEvent productEvent = event instanceof AgentEvent.AgentCompleted completed
                                ? new CodingAgentEvent.RunCompleted(SnapshotMapper.runResult(completed.result()))
                                : new CodingAgentEvent.RuntimeEvent(event);
                        var stage = eventSink.emit(productEvent);
                        if (stage == null) {
                            throw new IllegalStateException("coding event sink returned null stage");
                        }
                        return stage;
                    },
                    config.steeringMode(),
                    config.followUpMode(),
                    config.thinkingLevel(),
                    PrepareNextTurn.noop(),
                    ShouldStopAfterTurn.never(),
                    config.requestOptions()));
        } catch (RuntimeException e) {
            try {
                createdToolSet.close();
            } catch (RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        this.toolSet = createdToolSet;
        this.agent = createdAgent;
        this.reloadExecutor = projectContextConfig.enabled()
                ? Executors.newVirtualThreadPerTaskExecutor() : null;
    }

    /** Start a run with one text user message; concurrent runs fail fast. */
    public CompletionStage<CodingAgentRunResult> prompt(String text) {
        Message.User message = userMessage(text);
        CompletionStage<site.pplee.jcode.agentcore.LoopResult> runtimeStage;
        synchronized (lifecycleLock) {
            ensureOpen();
            if (running || reloading) {
                throw new IllegalStateException("coding-agent session is busy");
            }
            running = true;
            try {
                runtimeStage = agent.prompt(message);
            } catch (RuntimeException e) {
                running = false;
                throw e;
            }
        }

        var productStage = new CompletableFuture<CodingAgentRunResult>();
        runtimeStage.whenComplete((runtimeResult, failure) -> {
            CodingAgentRunResult productResult = null;
            Throwable completionFailure = failure;
            if (completionFailure == null) {
                try {
                    productResult = SnapshotMapper.runResult(runtimeResult);
                } catch (RuntimeException e) {
                    completionFailure = e;
                }
            }
            synchronized (lifecycleLock) {
                running = false;
            }
            if (completionFailure != null) {
                productStage.completeExceptionally(completionFailure);
            } else {
                productStage.complete(productResult);
            }
        });
        return productStage;
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
            if (running || reloading) {
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

    /**
     * Close owned resources and request cancellation of any active reload.
     *
     * <p>Waiting for loader shutdown is bounded to two seconds and skipped on a
     * loader worker. Uninterruptible file I/O keeps its stage pending and its
     * reload admission occupied until actual loading and cleanup have finished.
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
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
        if (errorFailure != null) {
            throw errorFailure;
        }
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
