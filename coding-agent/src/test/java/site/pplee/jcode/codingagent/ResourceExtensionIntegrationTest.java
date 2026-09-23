package site.pplee.jcode.codingagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.tool.AgentTool;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.codingagent.extension.CodingExtension;
import site.pplee.jcode.codingagent.extension.CommandOutcome;
import site.pplee.jcode.codingagent.extension.CustomRecordDraft;
import site.pplee.jcode.codingagent.extension.ExtensionCommand;
import site.pplee.jcode.codingagent.extension.CommandExecutionException;
import site.pplee.jcode.codingagent.context.ProjectContextConfig;
import site.pplee.jcode.codingagent.context.ProjectContextFile;
import site.pplee.jcode.codingagent.context.ProjectContextScope;
import site.pplee.jcode.codingagent.context.ProjectContextSnapshot;
import site.pplee.jcode.codingagent.resource.ResourceConfig;
import site.pplee.jcode.codingagent.model.ModelProfile;
import site.pplee.jcode.codingagent.session.CompactionEntry;
import site.pplee.jcode.codingagent.session.CustomEntry;
import site.pplee.jcode.codingagent.session.CustomMessageEntry;
import site.pplee.jcode.codingagent.session.SessionFileLockException;
import site.pplee.jcode.codingagent.settings.CompactionSettings;
import site.pplee.jcode.codingagent.support.ScriptedModelClient;
import site.pplee.jcode.codingagent.tool.CodingToolConfig;
import site.pplee.jcode.codingagent.tool.CodingToolPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ResourceExtensionIntegrationTest {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "model");

    @TempDir
    Path directory;

    @Test
    void commandPersistsFixedRecordsAndCustomMessageSurvivesReopen() throws Exception {
        var starts = new AtomicInteger();
        var shutdowns = new AtomicInteger();
        var extension = new CodingExtension() {
            @Override
            public String id() {
                return "notes";
            }

            @Override
            public List<ExtensionCommand> commands() {
                return List.of(new ExtensionCommand("save", (context, arguments) ->
                        CompletableFuture.completedStage(new CommandOutcome(
                                "saved",
                                List.of(
                                        new CustomRecordDraft.Data(
                                                "state", new ObjectMapper().createObjectNode()
                                                .put("private", "data-secret")),
                                        new CustomRecordDraft.Message(
                                                "note",
                                                List.of(new Content.Text(arguments.getFirst())),
                                                new ObjectMapper().createObjectNode()
                                                        .put("private", "details-secret"),
                                                false))))));
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> onSessionStarted(
                    site.pplee.jcode.codingagent.extension.ExtensionContext context
            ) {
                starts.incrementAndGet();
                return CompletableFuture.completedStage(null);
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> onSessionShutdown(
                    site.pplee.jcode.codingagent.extension.ExtensionContext context
            ) {
                shutdowns.incrementAndGet();
                return CompletableFuture.completedStage(null);
            }
        };
        var client = new ScriptedModelClient(request -> {
            assertEquals(1, request.messages().size());
            var projected = assertInstanceOf(Message.User.class, request.messages().getFirst());
            String text = projected.content().stream()
                    .filter(Content.Text.class::isInstance)
                    .map(Content.Text.class::cast)
                    .map(Content.Text::text)
                    .collect(java.util.stream.Collectors.joining());
            assertTrue(text.contains("extension_id=\"notes\""));
            assertTrue(text.contains("visible note"));
            assertFalse(text.contains("data-secret"));
            assertFalse(text.contains("details-secret"));
            return assistant("done");
        });
        Path sessions = Files.createDirectory(directory.resolve("sessions"));
        Path sessionFile;
        String customMessageId;
        try (var session = CodingAgentSession.create(config(client, List.of(extension)), sessions)) {
            assertEquals(1, starts.get());
            var result = session.executeCommand(
                    "notes", "save", List.of("visible note")).toCompletableFuture().join();
            assertEquals("saved", result.displayText());
            assertEquals(2, result.acceptedEntryIds().size());
            var entries = session.history().entries();
            assertInstanceOf(CustomEntry.class, entries.get(0));
            var message = assertInstanceOf(CustomMessageEntry.class, entries.get(1));
            assertEquals("notes", message.extensionId());
            assertFalse(message.display());
            customMessageId = message.id();

            session.continueRun().toCompletableFuture().join();
            sessionFile = session.sessionFile().orElseThrow();
        }
        assertEquals(1, shutdowns.get());

        var reopenedClient = new ScriptedModelClient(request -> {
            assertTrue(request.messages().stream()
                    .filter(Message.User.class::isInstance)
                    .map(Message.User.class::cast)
                    .flatMap(message -> message.content().stream())
                    .filter(Content.Text.class::isInstance)
                    .map(Content.Text.class::cast)
                    .anyMatch(text -> text.text().contains("visible note")));
            return assistant("reopened");
        });
        try (var reopened = CodingAgentSession.open(config(reopenedClient, List.of()), sessionFile)) {
            assertEquals(1, reopened.history().entries().stream()
                    .filter(CustomEntry.class::isInstance).count());
            assertEquals(1, reopened.history().entries().stream()
                    .filter(CustomMessageEntry.class::isInstance).count());
            reopened.branch(customMessageId);
            reopened.continueRun().toCompletableFuture().join();
        }
    }

    @Test
    void customStateUsesTheSelectedBranchAndVisibleMessagesSurviveRepeatedCompaction() {
        var mapper = new ObjectMapper();
        var extension = new CodingExtension() {
            @Override
            public String id() {
                return "branch-state";
            }

            @Override
            public List<ExtensionCommand> commands() {
                return List.of(new ExtensionCommand("append", (context, arguments) -> {
                    String value = arguments.getFirst();
                    return CompletableFuture.completedStage(CommandOutcome.records(List.of(
                            new CustomRecordDraft.Data("state",
                                    mapper.createObjectNode()
                                            .put("branch", value)
                                            .put("private", "data-secret")),
                            new CustomRecordDraft.Message(
                                    "note",
                                    List.of(new Content.Text((value + " visible ").repeat(60))),
                                    mapper.createObjectNode().put("private", "details-secret"),
                                    true))));
                }));
            }
        };
        var client = new ScriptedModelClient(
                request -> assistant("answer one ".repeat(60)),
                request -> assistant("answer two ".repeat(60)),
                request -> {
                    String material = request.messages().stream()
                            .filter(Message.User.class::isInstance)
                            .map(Message.User.class::cast)
                            .flatMap(message -> message.content().stream())
                            .filter(Content.Text.class::isInstance)
                            .map(Content.Text.class::cast)
                            .map(Content.Text::text)
                            .collect(java.util.stream.Collectors.joining());
                    assertTrue(material.contains("first visible"));
                    assertFalse(material.contains("data-secret"));
                    assertFalse(material.contains("details-secret"));
                    return assistant("summary-one");
                },
                request -> {
                    String text = request.messages().stream()
                            .filter(Message.User.class::isInstance)
                            .map(Message.User.class::cast)
                            .flatMap(message -> message.content().stream())
                            .filter(Content.Text.class::isInstance)
                            .map(Content.Text.class::cast)
                            .map(Content.Text::text)
                            .collect(java.util.stream.Collectors.joining());
                    assertTrue(text.contains("summary-one"));
                    assertTrue(text.contains("second visible"));
                    assertTrue(text.contains("third visible"));
                    return assistant("answer three ".repeat(60));
                },
                request -> {
                    String material = request.messages().stream()
                            .filter(Message.User.class::isInstance)
                            .map(Message.User.class::cast)
                            .flatMap(message -> message.content().stream())
                            .filter(Content.Text.class::isInstance)
                            .map(Content.Text.class::cast)
                            .map(Content.Text::text)
                            .collect(java.util.stream.Collectors.joining());
                    assertTrue(material.contains("summary-one"));
                    assertTrue(material.contains("second visible"));
                    assertFalse(material.contains("data-secret"));
                    assertFalse(material.contains("details-secret"));
                    return assistant("summary-two");
                },
                request -> {
                    String text = request.messages().stream()
                            .filter(Message.User.class::isInstance)
                            .map(Message.User.class::cast)
                            .flatMap(message -> message.content().stream())
                            .filter(Content.Text.class::isInstance)
                            .map(Content.Text.class::cast)
                            .map(Content.Text::text)
                            .collect(java.util.stream.Collectors.joining());
                    assertTrue(text.contains("summary-two"));
                    assertTrue(text.contains("third visible"));
                    assertTrue(text.contains("final visible"));
                    assertFalse(text.contains("data-secret"));
                    assertFalse(text.contains("details-secret"));
                    return assistant("final answer");
                });
        var base = config(client, List.of(extension));
        var configured = new CodingAgentConfig(
                base.workingDirectory(), base.model(), base.modelClient(), base.objectMapper(),
                base.thinkingLevel(), base.requestOptions(), base.steeringMode(), base.followUpMode(),
                base.customSystemPrompt(), base.appendSystemPrompt(), base.eventSink(), base.clock(),
                base.tools(), base.projectContext(), new CompactionSettings(false, 100, 10),
                Map.of(MODEL, new ModelProfile(OptionalInt.of(5_000), OptionalInt.of(500))),
                base.customization());

        try (var session = new CodingAgentSession(configured)) {
            session.executeCommand("branch-state", "append", List.of("first"))
                    .toCompletableFuture().join();
            String firstMessage = session.history().entries().stream()
                    .filter(CustomMessageEntry.class::isInstance)
                    .map(CustomMessageEntry.class::cast)
                    .findFirst().orElseThrow().id();
            session.continueRun().toCompletableFuture().join();

            session.executeCommand("branch-state", "append", List.of("second"))
                    .toCompletableFuture().join();
            session.continueRun().toCompletableFuture().join();
            String mainLeaf = session.history().currentEntryId().orElseThrow();

            session.branch(firstMessage);
            session.executeCommand("branch-state", "append", List.of("alternate"))
                    .toCompletableFuture().join();
            assertEquals(List.of("first", "alternate"), session.history().currentBranch().stream()
                    .filter(CustomEntry.class::isInstance)
                    .map(CustomEntry.class::cast)
                    .map(entry -> entry.data().get("branch").textValue())
                    .toList());
            session.branch(mainLeaf);
            assertEquals(List.of("first", "second"), session.history().currentBranch().stream()
                    .filter(CustomEntry.class::isInstance)
                    .map(CustomEntry.class::cast)
                    .map(entry -> entry.data().get("branch").textValue())
                    .toList());

            session.compact(null).toCompletableFuture().join();
            session.executeCommand("branch-state", "append", List.of("third"))
                    .toCompletableFuture().join();
            session.continueRun().toCompletableFuture().join();
            session.compact(null).toCompletableFuture().join();
            session.executeCommand("branch-state", "append", List.of("final"))
                    .toCompletableFuture().join();
            session.continueRun().toCompletableFuture().join();

            assertEquals(2, session.history().entries().stream()
                    .filter(CompactionEntry.class::isInstance).count());
        }
    }

    @Test
    void contextTransformIsRequestLocalAndObserverRunsBeforeHostSink() {
        var order = new ArrayList<String>();
        var extension = new CodingExtension() {
            @Override
            public String id() {
                return "context";
            }

            @Override
            public List<site.pplee.jcode.codingagent.extension.ExtensionContextTransform> contextTransforms() {
                return List.of((context, messages) -> {
                    var transformed = new ArrayList<>(messages);
                    transformed.add(StandardAgentMessage.of(new Message.User(
                            List.of(new Content.Text("temporary context")), Instant.EPOCH)));
                    return CompletableFuture.completedStage(transformed);
                });
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> observe(
                    site.pplee.jcode.codingagent.event.CodingAgentEvent event
            ) {
                order.add("extension");
                return CompletableFuture.completedStage(null);
            }
        };
        var client = new ScriptedModelClient(request -> {
            assertTrue(request.messages().stream()
                    .filter(Message.User.class::isInstance)
                    .map(Message.User.class::cast)
                    .flatMap(message -> message.content().stream())
                    .filter(Content.Text.class::isInstance)
                    .map(Content.Text.class::cast)
                    .anyMatch(text -> text.text().equals("temporary context")));
            return assistant("done");
        });
        var base = config(client, List.of(extension));
        var config = new CodingAgentConfig(
                base.workingDirectory(), base.model(), base.modelClient(), base.objectMapper(),
                base.thinkingLevel(), base.requestOptions(), base.steeringMode(), base.followUpMode(),
                base.customSystemPrompt(), base.appendSystemPrompt(), event -> {
                    order.add("host");
                    return CompletableFuture.completedStage(null);
                }, base.clock(), base.tools(), base.projectContext(), base.compaction(),
                base.modelProfiles(), base.customization());

        try (var session = new CodingAgentSession(config)) {
            session.prompt("normal input").toCompletableFuture().join();
            assertFalse(session.history().entries().toString().contains("temporary context"));
        }
        for (int index = 0; index + 1 < order.size(); index += 2) {
            assertEquals("extension", order.get(index));
            assertEquals("host", order.get(index + 1));
        }
    }

    @Test
    void extensionToolUsesTheExistingPolicyAndRuntimePipeline() {
        var executions = new AtomicInteger();
        AgentTool<com.fasterxml.jackson.databind.JsonNode> tool = new AgentTool<>() {
            @Override
            public String name() {
                return "extension_check";
            }

            @Override
            public Class<com.fasterxml.jackson.databind.JsonNode> argumentType() {
                return com.fasterxml.jackson.databind.JsonNode.class;
            }

            @Override
            public java.util.concurrent.CompletionStage<ToolExecutionResult> execute(
                    String toolCallId,
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    site.pplee.jcode.agentcore.tool.ToolUpdateSink updates,
                    site.pplee.jcode.ai.concurrent.CancellationSignal cancellation
            ) {
                executions.incrementAndGet();
                return CompletableFuture.completedStage(
                        ToolExecutionResult.success(List.of(new Content.Text("checked"))));
            }
        };
        var extension = new CodingExtension() {
            @Override
            public String id() {
                return "tools";
            }

            @Override
            public List<AgentTool<?>> tools() {
                return List.of(tool);
            }
        };
        var arguments = new ObjectMapper().createObjectNode().put("value", 1);
        var deniedClient = new ScriptedModelClient(
                request -> {
                    assertEquals(List.of("extension_check"),
                            request.tools().stream().map(spec -> spec.name()).toList());
                    return new Message.Assistant(
                            List.of(new Content.ToolCall("call", "extension_check", arguments)),
                            StopReason.TOOL_CALL, null, Usage.zero(), Instant.EPOCH, MODEL);
                },
                request -> {
                    var result = assertInstanceOf(
                            Message.ToolResultMessage.class, request.messages().getLast());
                    assertTrue(result.error());
                    return assistant("denied handled");
                });
        var denied = withTools(
                config(deniedClient, List.of(extension)),
                new CodingToolConfig(
                        java.util.Set.of(), null, null,
                        (request, cancellation) -> CompletableFuture.completedStage(
                                new CodingToolPolicy.Decision.Deny("not allowed"))));
        try (var session = new CodingAgentSession(denied)) {
            session.prompt("check").toCompletableFuture().join();
        }
        assertEquals(0, executions.get());

        var allowedClient = new ScriptedModelClient(
                request -> new Message.Assistant(
                        List.of(new Content.ToolCall("call", "extension_check", arguments)),
                        StopReason.TOOL_CALL, null, Usage.zero(), Instant.EPOCH, MODEL),
                request -> {
                    var result = assertInstanceOf(
                            Message.ToolResultMessage.class, request.messages().getLast());
                    assertFalse(result.error());
                    return assistant("allowed handled");
                });
        var allowed = withTools(
                config(allowedClient, List.of(extension)),
                new CodingToolConfig(java.util.Set.of(), null, null, CodingToolPolicy.allowAll()));
        try (var session = new CodingAgentSession(allowed)) {
            session.prompt("check").toCompletableFuture().join();
        }
        assertEquals(1, executions.get());
    }

    @Test
    void parallelExtensionToolsKeepCompletionEventsAndSourceOrderedHistoryDistinct() throws Exception {
        var firstResult = new CompletableFuture<ToolExecutionResult>();
        var secondResult = new CompletableFuture<ToolExecutionResult>();
        var bothStarted = new CountDownLatch(2);
        var secondCompletedEvent = new CountDownLatch(1);
        var executions = new AtomicInteger();
        AgentTool<com.fasterxml.jackson.databind.JsonNode> firstTool = controlledTool(
                "extension_first", firstResult, bothStarted, executions);
        AgentTool<com.fasterxml.jackson.databind.JsonNode> secondTool = controlledTool(
                "extension_second", secondResult, bothStarted, executions);
        var extension = new CodingExtension() {
            @Override
            public String id() {
                return "parallel-tools";
            }

            @Override
            public List<AgentTool<?>> tools() {
                return List.of(firstTool, secondTool);
            }
        };
        var arguments = new ObjectMapper().createObjectNode();
        var client = new ScriptedModelClient(
                request -> new Message.Assistant(
                        List.of(
                                new Content.ToolCall("first-call", "extension_first", arguments),
                                new Content.ToolCall("second-call", "extension_second", arguments)),
                        StopReason.TOOL_CALL, null, Usage.zero(), Instant.EPOCH, MODEL),
                request -> {
                    var results = request.messages().stream()
                            .filter(Message.ToolResultMessage.class::isInstance)
                            .map(Message.ToolResultMessage.class::cast)
                            .map(Message.ToolResultMessage::toolCallId)
                            .toList();
                    assertEquals(List.of("first-call", "second-call"), results);
                    return assistant("parallel tools done");
                });
        var completedEvents = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var configured = withEventSink(
                withTools(config(client, List.of(extension)),
                        new CodingToolConfig(java.util.Set.of(), null, null,
                                CodingToolPolicy.allowAll())),
                event -> {
                    if (event instanceof site.pplee.jcode.codingagent.event.CodingAgentEvent.RuntimeEvent runtime
                            && runtime.event() instanceof site.pplee.jcode.agentcore.event.AgentEvent.ToolCompleted done) {
                        completedEvents.add(done.result().toolCallId());
                        if (done.result().toolCallId().equals("second-call")) {
                            secondCompletedEvent.countDown();
                        }
                    }
                    return CompletableFuture.completedStage(null);
                });
        try (var session = new CodingAgentSession(configured)) {
            var operation = session.prompt("run both").toCompletableFuture();
            assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
            secondResult.complete(ToolExecutionResult.success(
                    List.of(new Content.Text("second completed"))));
            assertTrue(secondCompletedEvent.await(5, TimeUnit.SECONDS));
            firstResult.complete(ToolExecutionResult.success(
                    List.of(new Content.Text("first completed"))));
            operation.get(5, TimeUnit.SECONDS);

            assertEquals(List.of("second-call", "first-call"), completedEvents);
            assertEquals(List.of("first-call", "second-call"), session.history().currentBranch().stream()
                    .filter(site.pplee.jcode.codingagent.session.SessionMessageEntry.class::isInstance)
                    .map(site.pplee.jcode.codingagent.session.SessionMessageEntry.class::cast)
                    .map(entry -> entry.message().message())
                    .filter(Message.ToolResultMessage.class::isInstance)
                    .map(Message.ToolResultMessage.class::cast)
                    .map(Message.ToolResultMessage::toolCallId)
                    .toList());
            assertEquals(2, executions.get());
        } finally {
            firstResult.complete(ToolExecutionResult.failure("test cleanup"));
            secondResult.complete(ToolExecutionResult.failure("test cleanup"));
        }
    }

    @Test
    void resourceReloadPublishesNewPromptWithoutRecreatingExtension() throws Exception {
        Path skill = directory.resolve("skill/SKILL.md");
        writeSkill(skill, "initial description", "initial body");
        var reloads = new AtomicInteger();
        var extension = new CodingExtension() {
            @Override
            public String id() {
                return "reload-observer";
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> onResourcesReloaded(
                    site.pplee.jcode.codingagent.extension.ExtensionContext context,
                    site.pplee.jcode.codingagent.resource.ResourceSnapshot previous
            ) {
                reloads.incrementAndGet();
                assertTrue(context.resources().revision() > previous.revision());
                return CompletableFuture.completedStage(null);
            }
        };
        var client = new ScriptedModelClient(request -> {
            assertTrue(request.systemPrompt().contains("updated description"));
            assertFalse(request.systemPrompt().contains("updated body"));
            var user = assertInstanceOf(Message.User.class, request.messages().getFirst());
            assertEquals("/review untouched", assertInstanceOf(
                    Content.Text.class, user.content().getFirst()).text());
            return assistant("done");
        });
        var resources = ResourceConfig.builder().enabled(true).includeDefaults(false)
                .skillPaths(List.of(skill)).build();
        var base = config(client, List.of(extension));
        var configured = new CodingAgentConfig(
                base.workingDirectory(), base.model(), base.modelClient(), base.objectMapper(),
                base.thinkingLevel(), base.requestOptions(), base.steeringMode(), base.followUpMode(),
                base.customSystemPrompt(), base.appendSystemPrompt(), base.eventSink(), base.clock(),
                base.tools(), base.projectContext(), base.compaction(), base.modelProfiles(),
                new CustomizationConfig(resources, List.of(extension)));
        try (var session = new CodingAgentSession(configured)) {
            assertEquals("initial description",
                    session.resources().skill("review").orElseThrow().description());
            writeSkill(skill, "updated description", "updated body");
            var updated = session.reloadResources().toCompletableFuture().join();
            assertEquals(2, updated.revision());
            assertEquals(1, reloads.get());
            session.prompt("/review untouched").toCompletableFuture().join();
        }
    }

    @Test
    void contextTransformFailureKeepsTheLastValidCopyAndSinkFailureStopsTheModel() throws Exception {
        var diagnostics = new AtomicInteger();
        var thirdSawCleanArguments = new AtomicBoolean();
        var mapper = new ObjectMapper();
        var transformExtension = new CodingExtension() {
            @Override
            public String id() {
                return "context-errors";
            }

            @Override
            public List<site.pplee.jcode.codingagent.extension.ExtensionContextTransform> contextTransforms() {
                return List.of(
                        (context, messages) -> {
                            var next = new ArrayList<>(messages);
                            next.add(StandardAgentMessage.of(new Message.Assistant(
                                    List.of(new Content.Text("temporary text"), new Content.ToolCall(
                                            "temporary-call", "temporary-tool",
                                            mapper.createObjectNode().put("state", "clean"))),
                                    StopReason.TOOL_CALL, null, Usage.zero(), Instant.EPOCH, MODEL)));
                            return CompletableFuture.completedStage(next);
                        },
                        (context, messages) -> {
                            var assistant = assertInstanceOf(
                                    site.pplee.jcode.agentcore.message.StandardAgentMessage.class,
                                    messages.getLast()).message();
                            var call = assertInstanceOf(Content.ToolCall.class,
                                    assertInstanceOf(Message.Assistant.class, assistant).content().get(1));
                            ((com.fasterxml.jackson.databind.node.ObjectNode) call.arguments())
                                    .put("state", "polluted");
                            throw new IllegalStateException("expected transform failure");
                        },
                        (context, messages) -> {
                            var assistant = assertInstanceOf(
                                    site.pplee.jcode.agentcore.message.StandardAgentMessage.class,
                                    messages.getLast()).message();
                            var call = assertInstanceOf(Content.ToolCall.class,
                                    assertInstanceOf(Message.Assistant.class, assistant).content().get(1));
                            thirdSawCleanArguments.set("clean".equals(call.arguments().get("state").textValue()));
                            return CompletableFuture.completedStage(messages);
                        });
            }
        };
        var client = new ScriptedModelClient(request -> {
            assertTrue(request.messages().stream()
                    .filter(Message.Assistant.class::isInstance)
                    .map(Message.Assistant.class::cast)
                    .flatMap(message -> message.content().stream())
                    .filter(Content.Text.class::isInstance)
                    .map(Content.Text.class::cast)
                    .anyMatch(text -> text.text().equals("temporary text")));
            return assistant("done");
        });
        var base = config(client, List.of(transformExtension));
        var configured = withEventSink(base, event -> {
            if (event instanceof site.pplee.jcode.codingagent.event.CodingAgentEvent.ExtensionDiagnostic) {
                diagnostics.incrementAndGet();
            }
            return CompletableFuture.completedStage(null);
        });
        Path sessions = Files.createDirectory(directory.resolve("context-sessions"));
        Path sessionFile;
        try (var session = CodingAgentSession.create(configured, sessions)) {
            session.prompt("persisted input").toCompletableFuture().join();
            assertTrue(thirdSawCleanArguments.get());
            assertEquals(1, diagnostics.get());
            sessionFile = session.sessionFile().orElseThrow();
        }
        assertFalse(Files.readString(sessionFile).contains("temporary text"));

        var sinkFailure = new IllegalStateException("diagnostic sink failed");
        var modelCalls = new AtomicInteger();
        var failingClient = new ScriptedModelClient(request -> {
            modelCalls.incrementAndGet();
            return assistant("must not run");
        });
        var failingTransform = new CodingExtension() {
            @Override
            public String id() {
                return "failing-context";
            }

            @Override
            public List<site.pplee.jcode.codingagent.extension.ExtensionContextTransform> contextTransforms() {
                return List.of((context, messages) -> {
                    throw new IllegalArgumentException("transform failed");
                });
            }
        };
        var failingConfig = withEventSink(config(failingClient, List.of(failingTransform)), event ->
                event instanceof site.pplee.jcode.codingagent.event.CodingAgentEvent.ExtensionDiagnostic
                        ? CompletableFuture.failedStage(sinkFailure)
                        : CompletableFuture.completedStage(null));
        try (var session = new CodingAgentSession(failingConfig)) {
            var failure = assertThrows(CompletionException.class,
                    () -> session.prompt("blocked").toCompletableFuture().join());
            assertSame(sinkFailure, rootCause(failure));
            assertEquals(0, modelCalls.get());
        }
    }

    @Test
    void closeDefersShutdownUntilRunSettlesAndCancellationSkipsLaterTransforms() throws Exception {
        var firstEntered = new CountDownLatch(1);
        var cancellationObserved = new CountDownLatch(1);
        var releaseFirst = new CompletableFuture<Void>();
        var laterTransforms = new AtomicInteger();
        var shutdowns = new AtomicInteger();
        var extension = new CodingExtension() {
            @Override
            public String id() {
                return "closing-context";
            }

            @Override
            public List<site.pplee.jcode.codingagent.extension.ExtensionContextTransform> contextTransforms() {
                return List.of(
                        (context, messages) -> {
                            context.cancellation().onCancellation(cancellationObserved::countDown);
                            firstEntered.countDown();
                            return releaseFirst.thenApply(ignored -> messages);
                        },
                        (context, messages) -> {
                            laterTransforms.incrementAndGet();
                            return CompletableFuture.completedStage(messages);
                        });
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> onSessionShutdown(
                    site.pplee.jcode.codingagent.extension.ExtensionContext context
            ) {
                shutdowns.incrementAndGet();
                return CompletableFuture.completedStage(null);
            }
        };
        var modelCalls = new AtomicInteger();
        var client = new ScriptedModelClient(request -> {
            modelCalls.incrementAndGet();
            return assistant("must not run");
        });
        var session = new CodingAgentSession(config(client, List.of(extension)));
        var run = session.prompt("wait for context").toCompletableFuture();
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

        session.close();

        assertTrue(cancellationObserved.await(5, TimeUnit.SECONDS));
        assertFalse(run.isDone());
        assertEquals(0, shutdowns.get());
        releaseFirst.complete(null);
        assertTrue(run.get(5, TimeUnit.SECONDS).aborted());
        assertEquals(0, laterTransforms.get());
        assertEquals(0, modelCalls.get());
        assertEquals(1, shutdowns.get());
    }

    @Test
    void closeDoesNotTakeOverFinalizationWhileRunWaitsForShutdown() throws Exception {
        var transformEntered = new CountDownLatch(1);
        var cancellationObserved = new CountDownLatch(1);
        var releaseTransform = new CompletableFuture<Void>();
        var shutdownEntered = new CountDownLatch(1);
        var releaseShutdown = new CompletableFuture<Void>();
        var shutdowns = new AtomicInteger();
        var ownedClosed = new AtomicBoolean();
        var modelCalls = new AtomicInteger();
        var extension = new CodingExtension() {
            @Override
            public String id() {
                return "finalization-owner";
            }

            @Override
            public List<site.pplee.jcode.codingagent.extension.ExtensionContextTransform> contextTransforms() {
                return List.of((context, messages) -> {
                    context.cancellation().onCancellation(cancellationObserved::countDown);
                    transformEntered.countDown();
                    return releaseTransform.thenApply(ignored -> messages);
                });
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> onSessionShutdown(
                    site.pplee.jcode.codingagent.extension.ExtensionContext context
            ) {
                shutdowns.incrementAndGet();
                shutdownEntered.countDown();
                return releaseShutdown;
            }
        };
        var client = new ScriptedModelClient(request -> {
            modelCalls.incrementAndGet();
            return assistant("must not run");
        });
        var configured = config(client, List.of(extension));
        var manager = SessionManager.createFileBacked(
                new site.pplee.jcode.codingagent.session.SessionHeader(
                        UUID.randomUUID(), configured.clock().instant(), directory),
                Files.createDirectory(directory.resolve("finalization-sessions")),
                configured.clock());
        Path sessionFile = manager.filePath();
        var session = new CodingAgentSession(
                configured,
                (workingDirectory, ignored, revision, cancellation) ->
                        ProjectContextSnapshot.disabled(workingDirectory),
                manager,
                null,
                () -> ownedClosed.set(true));
        var run = session.prompt("wait for finalization").toCompletableFuture();
        assertTrue(transformEntered.await(5, TimeUnit.SECONDS));
        var close = CompletableFuture.runAsync(session::close);

        try {
            assertTrue(cancellationObserved.await(5, TimeUnit.SECONDS));
            assertFalse(close.isDone());
            releaseTransform.complete(null);
            assertTrue(shutdownEntered.await(5, TimeUnit.SECONDS));

            close.get(10, TimeUnit.SECONDS);

            assertFalse(run.isDone());
            assertFalse(ownedClosed.get());
            assertThrows(SessionFileLockException.class, () -> {
                try (var ignored = SessionFile.open(sessionFile)) {
                    // The accepted run still owns finalization and therefore the writer lock.
                }
            });

            releaseShutdown.complete(null);
            assertTrue(run.get(5, TimeUnit.SECONDS).aborted());
            assertTrue(ownedClosed.get());
            assertEquals(0, modelCalls.get());
            assertEquals(1, shutdowns.get());
            try (var ignored = SessionFile.open(sessionFile)) {
                // Finalization released the writer lock after shutdown completed.
            }
        } finally {
            releaseTransform.complete(null);
            releaseShutdown.complete(null);
            try {
                close.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Preserve the assertion failure while still releasing all test resources.
            }
            session.close();
        }
    }

    @Test
    void fatalContextTransformFailurePropagatesWithoutPersistingAModelError() {
        var fatal = new AssertionError("fatal transform");
        var modelCalls = new AtomicInteger();
        var extension = new CodingExtension() {
            @Override
            public String id() {
                return "fatal-context";
            }

            @Override
            public List<site.pplee.jcode.codingagent.extension.ExtensionContextTransform> contextTransforms() {
                return List.of((context, messages) -> CompletableFuture.failedStage(fatal));
            }
        };
        var client = new ScriptedModelClient(request -> {
            modelCalls.incrementAndGet();
            return assistant("must not run");
        });

        try (var session = new CodingAgentSession(config(client, List.of(extension)))) {
            var failure = assertThrows(CompletionException.class,
                    () -> session.prompt("fatal").toCompletableFuture().join());

            assertSame(fatal, rootCause(failure));
            assertEquals(0, modelCalls.get());
            assertEquals(0, session.history().entries().stream()
                    .filter(site.pplee.jcode.codingagent.session.SessionMessageEntry.class::isInstance)
                    .map(site.pplee.jcode.codingagent.session.SessionMessageEntry.class::cast)
                    .filter(entry -> entry.message().message() instanceof Message.Assistant)
                    .count());
        }
    }

    @Test
    void shutdownAttemptsEveryExtensionAndAggregatesDiagnosticFailures() {
        var order = new ArrayList<String>();
        var fatal = new AssertionError("fatal shutdown");
        var ordinary = new IllegalStateException("ordinary shutdown");
        var diagnostic = new IllegalArgumentException("diagnostic sink");
        CodingExtension first = shutdownExtension("first", order,
                CompletableFuture.completedStage(null));
        CodingExtension second = shutdownExtension("second", order,
                CompletableFuture.failedStage(ordinary));
        CodingExtension third = shutdownExtension("third", order,
                CompletableFuture.failedStage(fatal));
        var configured = withEventSink(
                config(new ScriptedModelClient(), List.of(first, second, third)),
                event -> event instanceof site.pplee.jcode.codingagent.event.CodingAgentEvent.ExtensionDiagnostic
                        ? CompletableFuture.failedStage(diagnostic)
                        : CompletableFuture.completedStage(null));
        var session = new CodingAgentSession(configured);

        var failure = assertThrows(AssertionError.class, session::close);

        assertSame(fatal, failure);
        assertEquals(List.of("third", "second", "first"), order);
        assertEquals(List.of(ordinary), List.of(fatal.getSuppressed()));
        assertEquals(List.of(diagnostic), List.of(ordinary.getSuppressed()));
    }

    @Test
    void resourceReloadIsExclusiveAndProjectContextReloadPreservesTextResources() throws Exception {
        Path skill = directory.resolve("reload-skill/SKILL.md");
        Path system = directory.resolve("reload-system.md");
        writeSkill(skill, "initial skill", "body one");
        Files.writeString(system, "initial system");
        var loadEntered = new CountDownLatch(1);
        var releaseLoad = new CountDownLatch(1);
        var loads = new AtomicInteger();
        CodingAgentSession.ContextLoader loader = (workingDirectory, ignored, revision, cancellation) -> {
            int call = loads.incrementAndGet();
            if (call == 2) {
                loadEntered.countDown();
                try {
                    assertTrue(releaseLoad.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }
            String content = call == 1 ? "initial context"
                    : call == 2 ? "updated context" : "latest context";
            Path path = directory.resolve("AGENTS.md");
            return new ProjectContextSnapshot(revision, workingDirectory,
                    List.of(new ProjectContextFile(
                            ProjectContextScope.PROJECT, path, path, content, content.length())),
                    List.of());
        };
        var contributionCalls = new AtomicInteger();
        AgentTool<com.fasterxml.jackson.databind.JsonNode> stableTool = new AgentTool<>() {
            @Override
            public String name() {
                return "stable_extension_tool";
            }

            @Override
            public Class<com.fasterxml.jackson.databind.JsonNode> argumentType() {
                return com.fasterxml.jackson.databind.JsonNode.class;
            }

            @Override
            public java.util.concurrent.CompletionStage<ToolExecutionResult> execute(
                    String toolCallId,
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    site.pplee.jcode.agentcore.tool.ToolUpdateSink updates,
                    site.pplee.jcode.ai.concurrent.CancellationSignal cancellation
            ) {
                return CompletableFuture.completedStage(ToolExecutionResult.success(List.of()));
            }
        };
        var extension = new CodingExtension() {
            @Override
            public String id() {
                return "stable-extension";
            }

            @Override
            public List<AgentTool<?>> tools() {
                contributionCalls.incrementAndGet();
                return List.of(stableTool);
            }
        };
        var requestNumber = new AtomicInteger();
        var client = new ScriptedModelClient(
                request -> {
                    assertEquals(1, requestNumber.incrementAndGet());
                    assertTrue(request.systemPrompt().contains("updated skill"));
                    assertTrue(request.systemPrompt().contains("updated system"));
                    assertTrue(request.systemPrompt().contains("updated context"));
                    assertEquals(1, request.tools().stream()
                            .filter(tool -> tool.name().equals("stable_extension_tool")).count());
                    return assistant("after resource reload");
                },
                request -> {
                    assertEquals(2, requestNumber.incrementAndGet());
                    assertTrue(request.systemPrompt().contains("updated skill"));
                    assertFalse(request.systemPrompt().contains("ignored later skill"));
                    assertTrue(request.systemPrompt().contains("updated system"));
                    assertFalse(request.systemPrompt().contains("ignored later system"));
                    assertTrue(request.systemPrompt().contains("latest context"));
                    return assistant("after context reload");
                });
        var resources = ResourceConfig.builder().enabled(true).includeDefaults(false)
                .skillPaths(List.of(skill)).systemPromptFile(system).build();
        var base = config(client, List.of(extension));
        var configured = new CodingAgentConfig(
                base.workingDirectory(), base.model(), base.modelClient(), base.objectMapper(),
                base.thinkingLevel(), base.requestOptions(), base.steeringMode(), base.followUpMode(),
                base.customSystemPrompt(), base.appendSystemPrompt(), base.eventSink(), base.clock(),
                base.tools(), ProjectContextConfig.project(), base.compaction(), base.modelProfiles(),
                new CustomizationConfig(resources, List.of(extension)));
        try (var session = new CodingAgentSession(configured, loader)) {
            writeSkill(skill, "updated skill", "body two");
            Files.writeString(system, "updated system");
            var reload = session.reloadResources().toCompletableFuture();
            assertTrue(loadEntered.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> session.prompt("busy"));
            assertThrows(IllegalStateException.class, () -> session.branch("missing"));
            assertThrows(IllegalStateException.class, () -> session.compact(null));
            releaseLoad.countDown();
            assertEquals(2, reload.get(5, TimeUnit.SECONDS).revision());
            assertEquals(1, contributionCalls.get());
            session.prompt("first request").toCompletableFuture().join();

            writeSkill(skill, "ignored later skill", "body three");
            Files.writeString(system, "ignored later system");
            session.reloadProjectContext().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("updated skill", session.resources().skill("review").orElseThrow().description());
            assertEquals("updated system", session.resources().systemPrompt().orElseThrow());
            session.prompt("second request").toCompletableFuture().join();
            assertEquals(1, contributionCalls.get());
        } finally {
            releaseLoad.countDown();
        }
    }

    @Test
    void closeWaitsForCommandAndBatchWriteFailureKeepsAcceptedPrefix() throws Exception {
        var handler = new CompletableFuture<CommandOutcome>();
        var shutdowns = new AtomicInteger();
        var closes = new AtomicInteger();
        class BorrowedExtension implements CodingExtension, AutoCloseable {
            @Override
            public String id() {
                return "borrowed";
            }

            @Override
            public List<ExtensionCommand> commands() {
                return List.of(new ExtensionCommand("wait", (context, arguments) -> handler));
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> onSessionShutdown(
                    site.pplee.jcode.codingagent.extension.ExtensionContext context
            ) {
                shutdowns.incrementAndGet();
                return CompletableFuture.completedStage(null);
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        }
        var borrowed = new BorrowedExtension();
        var session = new CodingAgentSession(config(new ScriptedModelClient(), List.of(borrowed)));
        var observed = session.executeCommand("borrowed", "wait", List.of()).toCompletableFuture();
        session.close();
        assertFalse(observed.isDone());
        assertEquals(0, shutdowns.get());
        assertEquals(0, closes.get());
        handler.complete(CommandOutcome.display("too late"));
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> observed.get(5, TimeUnit.SECONDS));
        assertEquals(1, shutdowns.get());
        assertEquals(0, closes.get());

        Path sessions = Files.createDirectory(directory.resolve("failed-command-sessions"));
        var header = new site.pplee.jcode.codingagent.session.SessionHeader(
                UUID.randomUUID(), Instant.EPOCH, directory);
        Path sessionFile;
        try (var file = SessionFile.create(sessions, header)) {
            sessionFile = file.path();
        }
        var ids = new AtomicInteger();
        var manager = SessionManager.openFileBacked(
                sessionFile,
                java.time.Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC),
                () -> "entry-" + ids.incrementAndGet(),
                channel -> buffer -> {
                    String json = StandardCharsets.UTF_8.decode(buffer.duplicate()).toString();
                    if (json.contains("\"customType\":\"second\"")) {
                        throw new java.io.IOException("injected second-record failure");
                    }
                    return channel.write(buffer);
                });
        var handlerCalls = new AtomicInteger();
        var batchExtension = new CodingExtension() {
            @Override
            public String id() {
                return "batch";
            }

            @Override
            public List<ExtensionCommand> commands() {
                return List.of(new ExtensionCommand("write", (context, arguments) -> {
                    handlerCalls.incrementAndGet();
                    return CompletableFuture.completedStage(CommandOutcome.records(List.of(
                            new CustomRecordDraft.Data("first", null),
                            new CustomRecordDraft.Data("second", null))));
                }));
            }
        };
        var noModel = new ScriptedModelClient(request -> assistant("must not run"));
        try (var failedSession = new CodingAgentSession(
                config(noModel, List.of(batchExtension)),
                (workingDirectory, ignored, revision, cancellation) ->
                        ProjectContextSnapshot.disabled(workingDirectory),
                manager)) {
            var failure = assertThrows(CompletionException.class, () -> failedSession
                    .executeCommand("batch", "write", List.of()).toCompletableFuture().join());
            var commandFailure = assertInstanceOf(
                    CommandExecutionException.class, failure.getCause());
            assertEquals(1, commandFailure.acceptedEntryIds().size());
            assertEquals(List.of("first"), failedSession.history().entries().stream()
                    .filter(CustomEntry.class::isInstance)
                    .map(CustomEntry.class::cast)
                    .map(CustomEntry::customType)
                    .toList());
            assertEquals(1, handlerCalls.get());

            assertThrows(CompletionException.class,
                    () -> failedSession.prompt("writer is poisoned").toCompletableFuture().join());
            assertTrue(noModel.requests().isEmpty());
            assertEquals(1, handlerCalls.get());
        }
    }

    private CodingAgentConfig config(
            ScriptedModelClient client,
            List<CodingExtension> extensions
    ) {
        return new CodingAgentConfig(
                directory, MODEL, client, new ObjectMapper(),
                null, null, null, null, null, null, null, null,
                null, null, CompactionSettings.disabled(), Map.of(),
                new CustomizationConfig(ResourceConfig.disabled(), extensions));
    }

    private static CodingAgentConfig withTools(
            CodingAgentConfig base,
            CodingToolConfig tools
    ) {
        return new CodingAgentConfig(
                base.workingDirectory(), base.model(), base.modelClient(), base.objectMapper(),
                base.thinkingLevel(), base.requestOptions(), base.steeringMode(), base.followUpMode(),
                base.customSystemPrompt(), base.appendSystemPrompt(), base.eventSink(), base.clock(),
                tools, base.projectContext(), base.compaction(), base.modelProfiles(),
                base.customization());
    }

    private static CodingAgentConfig withEventSink(
            CodingAgentConfig base,
            site.pplee.jcode.codingagent.event.CodingAgentEventSink sink
    ) {
        return new CodingAgentConfig(
                base.workingDirectory(), base.model(), base.modelClient(), base.objectMapper(),
                base.thinkingLevel(), base.requestOptions(), base.steeringMode(), base.followUpMode(),
                base.customSystemPrompt(), base.appendSystemPrompt(), sink, base.clock(),
                base.tools(), base.projectContext(), base.compaction(), base.modelProfiles(),
                base.customization());
    }

    private static AgentTool<com.fasterxml.jackson.databind.JsonNode> controlledTool(
            String name,
            CompletableFuture<ToolExecutionResult> result,
            CountDownLatch started,
            AtomicInteger executions
    ) {
        return new AgentTool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Class<com.fasterxml.jackson.databind.JsonNode> argumentType() {
                return com.fasterxml.jackson.databind.JsonNode.class;
            }

            @Override
            public java.util.concurrent.CompletionStage<ToolExecutionResult> execute(
                    String toolCallId,
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    site.pplee.jcode.agentcore.tool.ToolUpdateSink updates,
                    site.pplee.jcode.ai.concurrent.CancellationSignal cancellation
            ) {
                executions.incrementAndGet();
                started.countDown();
                return result;
            }
        };
    }

    private static CodingExtension shutdownExtension(
            String id,
            List<String> order,
            java.util.concurrent.CompletionStage<Void> result
    ) {
        return new CodingExtension() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> onSessionShutdown(
                    site.pplee.jcode.codingagent.extension.ExtensionContext context
            ) {
                order.add(id);
                return result;
            }
        };
    }

    private static Throwable rootCause(Throwable failure) {
        var current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Message.Assistant assistant(String text) {
        return new Message.Assistant(
                List.of(new Content.Text(text)), StopReason.STOP, null,
                Usage.zero(), Instant.EPOCH, MODEL);
    }

    private static void writeSkill(Path file, String description, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: review\ndescription: " + description
                + "\n---\n" + body);
    }
}
