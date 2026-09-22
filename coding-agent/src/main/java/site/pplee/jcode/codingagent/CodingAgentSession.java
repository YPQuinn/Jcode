package site.pplee.jcode.codingagent;

import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.ModelFailureKind;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.client.ModelRequestOptions;
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
import site.pplee.jcode.codingagent.model.ModelProfile;
import site.pplee.jcode.codingagent.compaction.BranchSummaryResult;
import site.pplee.jcode.codingagent.compaction.CompactionResult;
import site.pplee.jcode.codingagent.compaction.CompactionStatus;
import site.pplee.jcode.codingagent.compaction.ContextUsageEstimate;
import site.pplee.jcode.codingagent.compaction.ContextUsageEstimator;
import site.pplee.jcode.codingagent.compaction.SummaryCause;
import site.pplee.jcode.codingagent.compaction.CompactionPlanner;
import site.pplee.jcode.codingagent.compaction.SummaryGenerator;
import site.pplee.jcode.codingagent.compaction.SummaryMaterialSerializer;
import site.pplee.jcode.codingagent.compaction.SummaryDetailsExtractor;
import site.pplee.jcode.codingagent.settings.CompactionSettings;
import site.pplee.jcode.codingagent.prompt.SystemPromptBuilder;
import site.pplee.jcode.codingagent.session.LabelEntry;
import site.pplee.jcode.codingagent.session.SessionContextBuilder;
import site.pplee.jcode.codingagent.session.SessionDiagnostic;
import site.pplee.jcode.codingagent.session.SessionHeader;
import site.pplee.jcode.codingagent.session.SessionInfoEntry;
import site.pplee.jcode.codingagent.session.SessionEntry;
import site.pplee.jcode.codingagent.session.SessionSnapshot;
import site.pplee.jcode.codingagent.session.CompactionEntry;
import site.pplee.jcode.codingagent.session.BranchSummaryEntry;
import site.pplee.jcode.codingagent.session.SummaryDetails;
import site.pplee.jcode.codingagent.session.TokenEstimateSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.OptionalInt;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private final Map<ModelRef, ModelProfile> modelProfiles;
    private final CompactionSettings compactionSettings;
    private final site.pplee.jcode.ai.client.ModelClient modelClient;
    private final ModelRequestOptions requestOptions;
    private final CodingAgentEventSink productEventSink;
    private final AutoCloseable ownedResource;
    private final AtomicBoolean ownedResourceClosed = new AtomicBoolean();
    private final AtomicBoolean runtimeResourcesClosed = new AtomicBoolean();
    private volatile ModelSelection currentSelection;
    private final ExecutorService reloadExecutor;
    private ExecutorService maintenanceExecutor;
    // Remains set through callbacks, including callbacks that admit another reload.
    private final ThreadLocal<Boolean> reloadWorker = new ThreadLocal<>();
    private final ProjectContextConfig projectContextConfig;
    private final ContextLoader contextLoader;
    private final List<ToolSpec> toolSpecs;
    private final String customSystemPrompt;
    private final String appendSystemPrompt;
    private ProjectContextSnapshot projectContext;
    private volatile String currentSystemPrompt;
    private CancellationSource reloadSource;
    private CancellationSource summarySource;
    private CancellationSource runSource;
    private UsageObservation usageObservation;
    private volatile Throwable pendingInfrastructureFailure;
    private boolean skipAutomaticCompactionOnce;
    private final List<CodingAgentRunResult> attemptResults = new ArrayList<>();
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
        this.modelProfiles = config.modelProfiles();
        this.compactionSettings = config.compaction();
        this.modelClient = config.modelClient();
        this.requestOptions = config.requestOptions();
        this.productEventSink = config.eventSink();
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
            this.currentSystemPrompt = systemPrompt;
            validateCompactionBudget(config.model());
            var restored = SessionContextBuilder.build(sessionManager.snapshot());
            var context = new AgentContext(systemPrompt, restored.messages(), tools);
            var eventSink = config.eventSink();
            createdAgent = new Agent(new AgentConfig(
                    context,
                    config.model(),
                    config.modelClient(),
                    config.objectMapper(),
                    this::transformContext,
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

    /** Estimate the effective request view without invoking a model or mutating history. */
    public ContextUsageEstimate contextUsage() {
        synchronized (lifecycleLock) {
            ensureOpen();
            return contextUsage(sessionManager.snapshot(), currentSelection.selected());
        }
    }

    /** Generate and append one compaction checkpoint while the session is idle. */
    public CompletionStage<CompactionResult> compact(String additionalInstructions) {
        var result = new CompletableFuture<CompactionResult>();
        CancellationSource source;
        synchronized (lifecycleLock) {
            ensureOpen();
            if (running || reloading || historyOperation) {
                throw new IllegalStateException("coding-agent session is busy");
            }
            historyOperation = true;
            source = new CancellationSource();
            summarySource = source;
        }
        try {
            maintenanceExecutor().execute(() -> runManualCompaction(
                    additionalInstructions, source, result));
        } catch (RuntimeException failure) {
            result.completeExceptionally(finishSummaryOperation(source, failure));
        }
        return result.copy();
    }

    /** Move to an existing node and optionally carry a generated summary of the branch being left. */
    public CompletionStage<BranchSummaryResult> branchWithSummary(
            String targetEntryId,
            String additionalInstructions
    ) {
        Objects.requireNonNull(targetEntryId, "targetEntryId must not be null");
        var result = new CompletableFuture<BranchSummaryResult>();
        CancellationSource source;
        synchronized (lifecycleLock) {
            ensureOpen();
            if (running || reloading || historyOperation) {
                throw new IllegalStateException("coding-agent session is busy");
            }
            historyOperation = true;
            source = new CancellationSource();
            summarySource = source;
        }
        try {
            maintenanceExecutor().execute(() -> runBranchSummary(
                    targetEntryId, additionalInstructions, source, result));
        } catch (RuntimeException failure) {
            result.completeExceptionally(finishSummaryOperation(source, failure));
        }
        return result.copy();
    }

    private void runManualCompaction(
            String additionalInstructions,
            CancellationSource source,
            CompletableFuture<CompactionResult> result
    ) {
        try {
            var completed = compactNow(
                    SummaryCause.MANUAL, additionalInstructions, source.signal(), false);
            Throwable closeFailure = finishSummaryOperation(source, null);
            if (closeFailure != null) {
                result.completeExceptionally(closeFailure);
            } else {
                result.complete(completed);
            }
        } catch (Throwable failure) {
            failure = emitSummaryFailure(SummaryCause.MANUAL, failure, source.signal());
            result.completeExceptionally(finishSummaryOperation(
                    source, normalizeSummaryFailure(source, failure)));
        }
    }

    private void runBranchSummary(
            String targetEntryId,
            String additionalInstructions,
            CancellationSource source,
            CompletableFuture<BranchSummaryResult> result
    ) {
        try {
            var snapshot = sessionManager.snapshot();
            String fromId = snapshot.currentEntryId().orElseThrow(() ->
                    new IllegalStateException("cannot summarize a branch from an empty session"));
            snapshot.entry(targetEntryId).orElseThrow(() ->
                    new IllegalArgumentException("unknown session entry id: " + targetEntryId));
            var material = departingMaterial(snapshot, fromId, targetEntryId);
            BranchSummaryResult completed;
            if (material.isEmpty() || fromId.equals(targetEntryId)) {
                synchronized (lifecycleLock) {
                    source.signal().throwIfCancelled();
                    ensureOpen();
                    agent.replaceMessages(SessionContextBuilder.build(snapshot, targetEntryId).messages());
                    sessionManager.branch(targetEntryId);
                    usageObservation = null;
                }
                completed = new BranchSummaryResult(fromId, targetEntryId, targetEntryId, Optional.empty());
            } else {
                requireSummaryWritable();
                var budget = requireBudget(currentSelection.selected());
                emitSummaryEvent(new CodingAgentEvent.SummaryStarted(
                        SummaryCause.BRANCH, currentSelection.selected()));
                source.signal().throwIfCancelled();
                String serialized = serializeRecentBranchMaterial(
                        material, additionalInstructions, budget);
                var generated = new SummaryGenerator(modelClient).generate(
                        currentSelection.selected(), currentSelection.thinkingLevel(),
                        budget.summaryOutputTokens(), serialized, source.signal());
                source.signal().throwIfCancelled();
                synchronized (lifecycleLock) {
                    source.signal().throwIfCancelled();
                    ensureOpen();
                }
                BranchSummaryEntry entry;
                try {
                    entry = sessionManager.appendBranchSummary(
                            targetEntryId, fromId, generated.text(), currentSelection.selected(),
                            generated.usage(), SummaryDetailsExtractor.extract(
                                    material, departingSummaryDetails(snapshot, fromId, targetEntryId)));
                } catch (IOException failure) {
                    throw new SummaryInfrastructureFailure(failure);
                }
                synchronized (lifecycleLock) {
                    agent.replaceMessages(SessionContextBuilder.build(sessionManager.snapshot()).messages());
                    usageObservation = null;
                }
                emitSummaryEvent(new CodingAgentEvent.SummaryCompleted(
                        SummaryCause.BRANCH, entry.id(), currentSelection.selected(), generated.usage()));
                completed = new BranchSummaryResult(fromId, targetEntryId, entry.id(), Optional.of(entry.id()));
            }
            Throwable closeFailure = finishSummaryOperation(source, null);
            if (closeFailure != null) {
                result.completeExceptionally(closeFailure);
            } else {
                result.complete(completed);
            }
        } catch (Throwable failure) {
            failure = emitSummaryFailure(SummaryCause.BRANCH, failure, source.signal());
            result.completeExceptionally(finishSummaryOperation(
                    source, normalizeSummaryFailure(source, failure)));
        }
    }

    private CompactionResult compactNow(
            SummaryCause cause,
            String additionalInstructions,
            CancellationSignal cancellation,
            boolean requireThreshold
    ) {
        var model = currentSelection.selected();
        var budget = requireBudget(model);
        var snapshot = sessionManager.snapshot();
        var before = contextUsage(snapshot, model);
        var plan = new CompactionPlanner().plan(snapshot, compactionSettings.keepRecentTokens());
        if (plan.isEmpty()) {
            if (requireThreshold) {
                throw new IllegalStateException("no valid context boundary can be compacted");
            }
            return new CompactionResult(
                    CompactionStatus.SKIPPED, Optional.empty(), Optional.empty(),
                    before, before, Usage.zero(), "NOTHING_TO_COMPACT");
        }
        requireSummaryWritable();
        cancellation.throwIfCancelled();
        emitSummaryEvent(new CodingAgentEvent.SummaryStarted(cause, model));
        cancellation.throwIfCancelled();
        String material = new SummaryMaterialSerializer().serialize(
                plan.orElseThrow().material(), additionalInstructions);
        if (plan.orElseThrow().splitTurn()) {
            material = "[SPLIT TURN]\nThe retained suffix continues the same user task. "
                    + "Preserve the original request and the work needed to connect to that suffix.\n\n"
                    + material;
        }
        ensureSummaryInputFits(material, budget);
        var generated = new SummaryGenerator(modelClient).generate(
                model, currentSelection.thinkingLevel(), budget.summaryOutputTokens(),
                material, cancellation);
        cancellation.throwIfCancelled();

        long previousEstimatedTokens = ContextUsageEstimator.estimate(
                currentSystemPrompt, toolSpecs,
                SessionContextBuilder.buildRequestView(snapshot).messages());
        CompactionEntry prepared;
        try {
            prepared = sessionManager.prepareCompaction(
                    generated.text(), plan.orElseThrow().firstKeptEntryId(), before.tokens(),
                    before.source(), model, generated.usage(), SummaryDetailsExtractor.extract(
                            plan.orElseThrow().material(), inheritedSummaryDetails(snapshot)));
        } catch (IOException failure) {
            throw new SummaryInfrastructureFailure(failure);
        }
        var candidateSnapshot = withPreparedCompaction(snapshot, prepared);
        long candidateTokens = ContextUsageEstimator.estimate(
                currentSystemPrompt, toolSpecs,
                SessionContextBuilder.buildRequestView(candidateSnapshot).messages());
        if (candidateTokens >= previousEstimatedTokens) {
            throw new IllegalStateException("generated summary did not reduce the effective context");
        }
        if (requireThreshold && candidateTokens > budget.threshold()) {
            throw new IllegalStateException("generated summary is still above the model threshold");
        }

        synchronized (lifecycleLock) {
            cancellation.throwIfCancelled();
            ensureOpen();
        }
        CompactionEntry entry;
        try {
            entry = sessionManager.appendPreparedCompaction(prepared);
        } catch (IOException failure) {
            throw new SummaryInfrastructureFailure(failure);
        }
        synchronized (lifecycleLock) {
            usageObservation = null;
        }
        var after = contextUsage(sessionManager.snapshot(), model);
        emitSummaryEvent(new CodingAgentEvent.SummaryCompleted(
                cause, entry.id(), model, generated.usage()));
        return new CompactionResult(
                CompactionStatus.COMPACTED, Optional.of(entry.id()),
                Optional.of(entry.firstKeptEntryId()), before, after,
                generated.usage(), after.tokens() > budget.threshold()
                ? "COMPACTED_ABOVE_THRESHOLD" : null);
    }

    private CompletionStage<List<site.pplee.jcode.agentcore.message.AgentMessage>> transformContext(
            List<site.pplee.jcode.agentcore.message.AgentMessage> ignoredRawMessages,
            CancellationSignal cancellation
    ) {
        try {
            var snapshot = sessionManager.snapshot();
            boolean skip;
            synchronized (lifecycleLock) {
                skip = skipAutomaticCompactionOnce;
                skipAutomaticCompactionOnce = false;
            }
            if (!skip && compactionSettings.enabled()) {
                var budget = budget(currentSelection.selected());
                if (budget.isPresent()) {
                    var usage = contextUsage(snapshot, currentSelection.selected());
                    if (usage.tokens() > budget.orElseThrow().threshold()) {
                        compactNow(SummaryCause.THRESHOLD, null, cancellation, true);
                        snapshot = sessionManager.snapshot();
                    }
                }
            }
            return CompletableFuture.completedFuture(
                    SessionContextBuilder.buildRequestView(snapshot).messages());
        } catch (Throwable failure) {
            var infrastructure = infrastructureFailure(failure);
            if (compactionSettings.enabled()) {
                failure = emitSummaryFailure(SummaryCause.THRESHOLD, failure, cancellation);
            }
            if (infrastructure == null) {
                infrastructure = infrastructureFailure(failure);
            }
            if (infrastructure != null) {
                pendingInfrastructureFailure = infrastructure;
            }
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static SessionSnapshot withPreparedCompaction(
            SessionSnapshot snapshot,
            CompactionEntry compaction
    ) {
        var entries = new ArrayList<SessionEntry>(snapshot.entries());
        entries.add(compaction);
        return new SessionSnapshot(
                snapshot.header(), entries, compaction.id(), snapshot.name().orElse(null), Map.of());
    }

    private ContextUsageEstimate contextUsage(SessionSnapshot snapshot, ModelRef model) {
        var profile = modelProfiles.getOrDefault(model, ModelProfile.empty());
        OptionalInt window = profile.contextWindow();
        OptionalInt threshold = OptionalInt.empty();
        var maybeBudget = budget(model);
        if (maybeBudget.isPresent()) {
            threshold = OptionalInt.of(maybeBudget.orElseThrow().threshold());
        }
        var observation = usageObservation;
        if (observation != null
                && observation.model().equals(model)
                && Objects.equals(observation.compactionEntryId(), latestCompactionId(snapshot))) {
            var branch = snapshot.currentBranch();
            int observedIndex = -1;
            for (int index = 0; index < branch.size(); index++) {
                if (branch.get(index).id().equals(observation.entryId())) {
                    observedIndex = index;
                    break;
                }
            }
            if (observedIndex >= 0) {
                long tail = 0;
                for (int index = observedIndex + 1; index < branch.size(); index++) {
                    if (branch.get(index) instanceof site.pplee.jcode.codingagent.session.SessionMessageEntry message) {
                        tail += ContextUsageEstimator.estimateMessage(message.message());
                    } else if (branch.get(index) instanceof BranchSummaryEntry summary) {
                        tail += Math.max(1, (summary.summary().length() + 3L) / 4L);
                    }
                }
                return new ContextUsageEstimate(
                        observation.tokens() + tail,
                        TokenEstimateSource.USAGE_PLUS_TAIL,
                        window,
                        threshold);
            }
        }
        long estimated = ContextUsageEstimator.estimate(
                currentSystemPrompt,
                toolSpecs,
                SessionContextBuilder.buildRequestView(snapshot).messages());
        return new ContextUsageEstimate(
                estimated, TokenEstimateSource.FULL_ESTIMATE, window, threshold);
    }

    private Optional<Budget> budget(ModelRef model) {
        var profile = modelProfiles.getOrDefault(model, ModelProfile.empty());
        if (profile.contextWindow().isEmpty()) {
            return Optional.empty();
        }
        int reserve = Math.max(
                compactionSettings.reserveTokens(),
                requestOptions.maxOutputTokens() == null ? 0 : requestOptions.maxOutputTokens());
        int window = profile.contextWindow().getAsInt();
        int threshold = window - reserve;
        int summaryOutput = Math.floorDiv(compactionSettings.reserveTokens() * 4, 5);
        if (profile.maxOutputTokens().isPresent()) {
            summaryOutput = Math.min(summaryOutput, profile.maxOutputTokens().getAsInt());
        }
        if (window <= reserve || compactionSettings.keepRecentTokens() >= threshold || summaryOutput <= 0) {
            throw new IllegalArgumentException(
                    "invalid compaction budget for model " + model.modelId());
        }
        if (requestOptions.maxOutputTokens() != null
                && profile.maxOutputTokens().isPresent()
                && requestOptions.maxOutputTokens() > profile.maxOutputTokens().getAsInt()) {
            throw new IllegalArgumentException("request maxOutputTokens exceeds the model profile limit");
        }
        return Optional.of(new Budget(window, threshold, summaryOutput));
    }

    private Budget requireBudget(ModelRef model) {
        return budget(model).orElseThrow(() ->
                new IllegalStateException("model context window is unknown; compaction is unavailable"));
    }

    private void validateCompactionBudget(ModelRef model) {
        if (compactionSettings.enabled()) {
            budget(model);
        }
    }

    private void ensureSummaryInputFits(String material, Budget budget) {
        if (!summaryInputFits(material, budget)) {
            throw new IllegalStateException("summary input does not fit the known model context window");
        }
    }

    private String serializeRecentBranchMaterial(
            List<site.pplee.jcode.agentcore.message.AgentMessage> material,
            String additionalInstructions,
            Budget budget
    ) {
        var serializer = new SummaryMaterialSerializer();
        for (int start = 0; start < material.size(); start++) {
            String serialized = serializer.serialize(
                    material.subList(start, material.size()), additionalInstructions);
            if (start > 0) {
                serialized = "[EARLIER BRANCH MATERIAL OMITTED]\n" + serialized;
            }
            if (summaryInputFits(serialized, budget)) {
                return serialized;
            }
        }
        throw new IllegalStateException("recent branch material does not fit the model context window");
    }

    private static boolean summaryInputFits(String material, Budget budget) {
        long input = (SummaryGenerator.SYSTEM_PROMPT.length() + material.length() + 3L) / 4L;
        return input + budget.summaryOutputTokens() <= budget.window();
    }

    private static String latestCompactionId(SessionSnapshot snapshot) {
        String id = null;
        for (var entry : snapshot.currentBranch()) {
            if (entry instanceof CompactionEntry compaction) {
                id = compaction.id();
            }
        }
        return id;
    }

    private static List<SummaryDetails> inheritedSummaryDetails(SessionSnapshot snapshot) {
        var details = new ArrayList<SummaryDetails>();
        for (var entry : snapshot.currentBranch()) {
            if (entry instanceof CompactionEntry compaction) {
                details.add(compaction.details());
            } else if (entry instanceof BranchSummaryEntry summary) {
                details.add(summary.details());
            }
        }
        return List.copyOf(details);
    }

    private static List<SummaryDetails> departingSummaryDetails(
            SessionSnapshot snapshot,
            String fromId,
            String targetId
    ) {
        var from = snapshot.branch(fromId);
        var target = snapshot.branch(targetId);
        int common = 0;
        while (common < from.size() && common < target.size()
                && from.get(common).id().equals(target.get(common).id())) {
            common++;
        }
        var details = new ArrayList<SummaryDetails>();
        for (int index = common; index < from.size(); index++) {
            if (from.get(index) instanceof CompactionEntry compaction) {
                details.add(compaction.details());
            } else if (from.get(index) instanceof BranchSummaryEntry summary) {
                details.add(summary.details());
            }
        }
        return List.copyOf(details);
    }

    private static List<site.pplee.jcode.agentcore.message.AgentMessage> departingMaterial(
            SessionSnapshot snapshot,
            String fromId,
            String targetId
    ) {
        var from = snapshot.branch(fromId);
        var target = snapshot.branch(targetId);
        int common = 0;
        while (common < from.size() && common < target.size()
                && from.get(common).id().equals(target.get(common).id())) {
            common++;
        }
        var material = new ArrayList<site.pplee.jcode.agentcore.message.AgentMessage>();
        for (int index = common; index < from.size(); index++) {
            var entry = from.get(index);
            if (entry instanceof site.pplee.jcode.codingagent.session.SessionMessageEntry message) {
                material.add(message.message());
            } else if (entry instanceof CompactionEntry compaction) {
                material.add(syntheticSummary("compaction", compaction.summary(), compaction.timestamp()));
            } else if (entry instanceof BranchSummaryEntry summary) {
                material.add(syntheticSummary("branch-summary", summary.summary(), summary.timestamp()));
            }
        }
        return List.copyOf(material);
    }

    private static StandardAgentMessage syntheticSummary(
            String kind,
            String summary,
            java.time.Instant timestamp
    ) {
        return StandardAgentMessage.of(new Message.User(
                List.of(new Content.Text("[" + kind + "]\n" + summary)), timestamp));
    }

    private ExecutorService maintenanceExecutor() {
        synchronized (lifecycleLock) {
            ensureOpen();
            if (maintenanceExecutor == null) {
                maintenanceExecutor = Executors.newVirtualThreadPerTaskExecutor();
            }
            return maintenanceExecutor;
        }
    }

    private Throwable finishSummaryOperation(CancellationSource source, Throwable failure) {
        synchronized (lifecycleLock) {
            if (summarySource == source) {
                summarySource = null;
                historyOperation = false;
            }
        }
        if (!closed) {
            return failure;
        }
        failure = closeRuntimeResources(failure, false);
        return closeManager(failure);
    }

    private static Throwable normalizeSummaryFailure(CancellationSource source, Throwable failure) {
        if (!source.signal().isCancelled() || failure instanceof CancellationException) {
            return failure;
        }
        var cancelled = new CancellationException("summary operation cancelled");
        cancelled.addSuppressed(failure);
        return cancelled;
    }

    private Throwable emitSummaryFailure(
            SummaryCause cause,
            Throwable failure,
            CancellationSignal cancellation
    ) {
        try {
            if (cancellation.isCancelled() || failure instanceof CancellationException) {
                emitSummaryEvent(new CodingAgentEvent.SummaryCancelled(cause));
            } else {
                var reported = infrastructureFailure(failure);
                emitSummaryEvent(new CodingAgentEvent.SummaryFailed(
                        cause, (reported == null ? failure : reported).getClass().getSimpleName()));
            }
        } catch (Throwable eventFailure) {
            var originalInfrastructure = infrastructureFailure(failure);
            if (originalInfrastructure != null) {
                if (originalInfrastructure != eventFailure) {
                    originalInfrastructure.addSuppressed(eventFailure);
                }
                return originalInfrastructure;
            }
            var eventInfrastructure = Objects.requireNonNull(
                    infrastructureFailure(eventFailure),
                    "summary event failure must be classified as infrastructure");
            if (eventInfrastructure != failure) {
                eventInfrastructure.addSuppressed(failure);
            }
            return eventInfrastructure;
        }
        var infrastructure = infrastructureFailure(failure);
        return infrastructure == null ? failure : infrastructure;
    }

    private void emitSummaryEvent(CodingAgentEvent event) {
        try {
            emitEvent(event);
        } catch (Throwable failure) {
            throw new SummaryInfrastructureFailure(unwrapCompletionFailure(failure));
        }
    }

    private void requireSummaryWritable() {
        try {
            sessionManager.requireWritable();
        } catch (IOException failure) {
            throw new SummaryInfrastructureFailure(failure);
        }
    }

    private void emitEvent(CodingAgentEvent event) {
        var stage = productEventSink.emit(event);
        if (stage == null) {
            throw new IllegalStateException("coding event sink returned null stage");
        }
        stage.toCompletableFuture().join();
    }

    private static Throwable infrastructureFailure(Throwable failure) {
        if (failure instanceof SummaryInfrastructureFailure infrastructure) {
            return infrastructure.getCause();
        }
        if (failure.getCause() != null && failure.getCause() != failure) {
            var cause = infrastructureFailure(failure.getCause());
            if (cause != null) {
                return cause;
            }
        }
        for (var suppressed : failure.getSuppressed()) {
            var cause = infrastructureFailure(suppressed);
            if (cause != null) {
                return cause;
            }
        }
        return null;
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        if ((failure instanceof CompletionException || failure instanceof java.util.concurrent.ExecutionException)
                && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static final class SummaryInfrastructureFailure extends RuntimeException {
        private SummaryInfrastructureFailure(Throwable cause) {
            super("summary infrastructure operation failed", cause);
        }
    }

    private record Budget(int window, int threshold, int summaryOutputTokens) {
    }

    private record UsageObservation(
            ModelRef model,
            String entryId,
            String compactionEntryId,
            long tokens
    ) {
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
            runSource = new CancellationSource();
            attemptResults.clear();
            pendingInfrastructureFailure = null;
            try {
                runtimeStage = starter.get();
            } catch (RuntimeException | Error failure) {
                running = false;
                runSource = null;
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
        if (failure != null) {
            finishRunFinal(null, failure, productStage);
            return;
        }
        CodingAgentRunResult first;
        try {
            first = SnapshotMapper.runResult(runtimeResult);
            attemptResults.add(first);
        } catch (RuntimeException | Error mappingFailure) {
            finishRunFinal(null, mappingFailure, productStage);
            return;
        }
        if (!shouldRecoverOverflow(first.finalMessage())) {
            finishRunFinal(first, null, productStage);
            return;
        }

        CancellationSource operationSource;
        boolean cancelled;
        synchronized (lifecycleLock) {
            operationSource = runSource;
            cancelled = operationSource == null
                    || operationSource.signal().isCancelled() || closed;
            if (!cancelled) {
                summarySource = operationSource;
            }
        }
        if (cancelled) {
            finishRunFinal(null, new CancellationException("coding-agent operation cancelled"), productStage);
            return;
        }
        try {
            compactNow(SummaryCause.OVERFLOW, null, operationSource.signal(), true);
            CompletionStage<site.pplee.jcode.agentcore.LoopResult> secondStage;
            synchronized (lifecycleLock) {
                operationSource.signal().throwIfCancelled();
                ensureOpen();
                if (runSource != operationSource) {
                    throw new CancellationException("coding-agent operation cancelled");
                }
                skipAutomaticCompactionOnce = true;
                secondStage = agent.continueAfterFailure();
            }
            secondStage.whenComplete((second, secondFailure) -> {
                if (secondFailure != null) {
                    finishRunFinal(null, secondFailure, productStage);
                    return;
                }
                try {
                    var mapped = SnapshotMapper.runResult(second);
                    attemptResults.add(mapped);
                    finishRunFinal(combineAttemptResults(), null, productStage);
                } catch (RuntimeException | Error mappingFailure) {
                    finishRunFinal(null, mappingFailure, productStage);
                }
            });
        } catch (Throwable recoveryFailure) {
            var infrastructure = infrastructureFailure(recoveryFailure);
            Throwable reportedFailure = emitSummaryFailure(
                    SummaryCause.OVERFLOW, recoveryFailure, operationSource.signal());
            synchronized (lifecycleLock) {
                summarySource = null;
            }
            if (infrastructure == null) {
                infrastructure = infrastructureFailure(reportedFailure);
            }
            if (operationSource.signal().isCancelled()) {
                finishRunFinal(null, new CancellationException("overflow recovery cancelled"), productStage);
            } else if (infrastructure != null) {
                finishRunFinal(null, infrastructure, productStage);
            } else {
                finishRunFinal(first, null, productStage);
            }
        }
    }

    private void finishRunFinal(
            CodingAgentRunResult productResult,
            Throwable failure,
            CompletableFuture<CodingAgentRunResult> productStage
    ) {
        Throwable completionFailure = pendingInfrastructureFailure != null
                ? pendingInfrastructureFailure : failure;
        if (completionFailure == null) {
            try {
                emitEvent(new CodingAgentEvent.RunCompleted(productResult));
            } catch (Throwable eventFailure) {
                completionFailure = eventFailure;
            }
        }
        boolean closeManager;
        synchronized (lifecycleLock) {
            if (completionFailure != null && !closed) {
                try {
                    agent.replaceMessages(
                            SessionContextBuilder.build(sessionManager.snapshot()).messages());
                } catch (RuntimeException | Error alignmentFailure) {
                    completionFailure.addSuppressed(alignmentFailure);
                }
            }
            running = false;
            summarySource = null;
            runSource = null;
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

    private boolean shouldRecoverOverflow(Message.Assistant assistant) {
        return compactionSettings.enabled()
                && assistant.stopReason() == StopReason.ERROR
                && assistant.metadata().failureKind()
                        .filter(kind -> kind == ModelFailureKind.CONTEXT_OVERFLOW)
                        .isPresent()
                && budget(currentSelection.selected()).isPresent();
    }

    private CodingAgentRunResult combineAttemptResults() {
        var messages = new ArrayList<site.pplee.jcode.agentcore.message.AgentMessage>();
        attemptResults.forEach(result -> messages.addAll(result.newMessages()));
        return new CodingAgentRunResult(
                messages, attemptResults.getLast().finalMessage());
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
                if (runSource != null) {
                    runSource.cancel();
                }
                agent.abort();
                if (summarySource != null) {
                    summarySource.cancel();
                }
            } else if (reloading && reloadSource != null) {
                reloadSource.cancel();
            } else if (historyOperation && summarySource != null) {
                summarySource.cancel();
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
                    currentSystemPrompt = prompt;
                    projectContext = candidate;
                    usageObservation = null;
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
            validateCompactionBudget(targetModel);
            synchronized (lifecycleLock) {
                ensureOpen();
                agent.updateModel(targetModel, thinkingLevel);
                var updated = runtimeSelection(targetModel, thinkingLevel);
                currentSelection = updated;
                usageObservation = null;
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
                usageObservation = null;
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
                usageObservation = null;
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
        boolean deferRuntimeClose;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            if (reloadSource != null) {
                reloadSource.cancel();
            }
            if (summarySource != null) {
                summarySource.cancel();
            }
            if (runSource != null) {
                runSource.cancel();
            }
            deferRuntimeClose = historyOperation;
        }
        if (deferRuntimeClose) {
            if (reloadExecutor != null) {
                reloadExecutor.shutdown();
            }
            if (maintenanceExecutor != null) {
                maintenanceExecutor.shutdown();
            }
            return;
        }
        Throwable failure = closeRuntimeResources(null, true);
        boolean closeManagerNow;
        synchronized (lifecycleLock) {
            closeManagerNow = !running && !historyOperation;
        }
        if (closeManagerNow) {
            failure = closeManager(failure);
        }
        rethrowCloseFailure(failure);
    }

    private Throwable closeRuntimeResources(Throwable existingFailure, boolean interruptMaintenance) {
        if (!runtimeResourcesClosed.compareAndSet(false, true)) {
            return existingFailure;
        }
        Throwable failure = existingFailure;
        try {
            agent.close();
        } catch (RuntimeException | Error closeFailure) {
            failure = combineFailures(failure, closeFailure);
        }
        if (reloadExecutor != null) {
            reloadExecutor.shutdownNow();
            if (!Boolean.TRUE.equals(reloadWorker.get())) {
                try {
                    reloadExecutor.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException closeFailure) {
                    Thread.currentThread().interrupt();
                    failure = combineFailures(failure, new IllegalStateException(
                            "interrupted while closing project context loader", closeFailure));
                }
            }
        }
        if (maintenanceExecutor != null) {
            if (interruptMaintenance) {
                maintenanceExecutor.shutdownNow();
            } else {
                maintenanceExecutor.shutdown();
            }
        }
        try {
            toolSet.close();
        } catch (RuntimeException | Error closeFailure) {
            failure = combineFailures(failure, closeFailure);
        }
        return failure;
    }

    private static Throwable combineFailures(Throwable failure, Throwable added) {
        if (failure == null) {
            return added;
        }
        failure.addSuppressed(added);
        return failure;
    }

    private static void rethrowCloseFailure(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure != null) {
            throw new IllegalStateException("unexpected checked session close failure", failure);
        }
    }

    private CompletionStage<Void> emitProductEvent(
            AgentEvent event,
            CodingAgentEventSink eventSink
    ) {
        if (event instanceof AgentEvent.MessageCompleted completed) {
            var infrastructureFailure = pendingInfrastructureFailure;
            if (infrastructureFailure != null) {
                return CompletableFuture.failedFuture(infrastructureFailure);
            }
            var message = SnapshotMapper.agentMessage(completed.message());
            if (!(message instanceof StandardAgentMessage standard)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "phase-four sessions only persist standard agent messages"));
            }
            try {
                var selection = currentSelection;
                var entry = sessionManager.appendCompletedMessage(
                        standard, selection.selected(), selection.thinkingLevel());
                if (standard.message() instanceof Message.Assistant assistant) {
                    long usageTokens = usageTokens(assistant.usage());
                    if (!assistant.stopReason().isTerminalFailure() && usageTokens > 0) {
                        usageObservation = new UsageObservation(
                                selection.selected(), entry.id(),
                                latestCompactionId(sessionManager.snapshot()), usageTokens);
                    }
                }
            } catch (IOException | RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        if (event instanceof AgentEvent.AgentCompleted) {
            return CompletableFuture.completedFuture(null);
        }
        CodingAgentEvent productEvent = new CodingAgentEvent.RuntimeEvent(event);
        var stage = eventSink.emit(productEvent);
        if (stage == null) {
            throw new IllegalStateException("coding event sink returned null stage");
        }
        return stage;
    }

    private static long usageTokens(Usage usage) {
        if (usage.totalTokens() > 0) {
            return usage.totalTokens();
        }
        return usage.input() + usage.output() + usage.cacheRead() + usage.cacheWrite();
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
        if (!closeManager) {
            return existingFailure;
        }
        var failure = closeRuntimeResources(existingFailure, false);
        return closeManager(failure);
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
