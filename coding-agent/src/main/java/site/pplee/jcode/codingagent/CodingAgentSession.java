package site.pplee.jcode.codingagent;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.agentcore.Agent;
import site.pplee.jcode.agentcore.AgentConfig;
import site.pplee.jcode.agentcore.AgentContext;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.message.ContextTransformer;
import site.pplee.jcode.agentcore.message.MessageProjector;
import site.pplee.jcode.agentcore.tool.AfterToolCall;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;
import site.pplee.jcode.agentcore.turn.PrepareNextTurn;
import site.pplee.jcode.agentcore.turn.ShouldStopAfterTurn;
import site.pplee.jcode.codingagent.event.CodingAgentEvent;
import site.pplee.jcode.codingagent.internal.SnapshotMapper;
import site.pplee.jcode.codingagent.prompt.SystemPromptBuilder;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Headless product facade for one coding-agent conversation.
 *
 * <p>The session owns its underlying {@link Agent}, serializes run admission
 * and queue acceptance, and exposes only immutable product snapshots.
 */
public final class CodingAgentSession implements AutoCloseable {
    private final Object lifecycleLock = new Object();
    private final Agent agent;
    private final Path workingDirectory;
    private final Clock clock;
    private boolean running;
    private boolean closed;

    public CodingAgentSession(CodingAgentConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        this.workingDirectory = config.workingDirectory();
        this.clock = config.clock();

        var tools = BuiltInTools.create(workingDirectory, config.tools());
        var toolSpecs = tools.stream().map(tool -> tool.spec()).toList();
        String systemPrompt = SystemPromptBuilder.build(
                workingDirectory,
                toolSpecs,
                config.customSystemPrompt(),
                config.appendSystemPrompt());
        var context = new AgentContext(systemPrompt, List.of(), tools);
        var eventSink = config.eventSink();
        this.agent = new Agent(new AgentConfig(
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
    }

    /** Start a run with one text user message; concurrent runs fail fast. */
    public CompletionStage<CodingAgentRunResult> prompt(String text) {
        Message.User message = userMessage(text);
        CompletionStage<site.pplee.jcode.agentcore.LoopResult> runtimeStage;
        synchronized (lifecycleLock) {
            ensureOpen();
            if (running) {
                throw new IllegalStateException("coding-agent session is already running");
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

    /** Request cancellation of the current run, if any. */
    public void abort() {
        synchronized (lifecycleLock) {
            if (running) {
                agent.abort();
            }
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

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        agent.close();
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
