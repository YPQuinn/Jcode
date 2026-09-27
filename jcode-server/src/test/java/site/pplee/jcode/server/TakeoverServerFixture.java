package site.pplee.jcode.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.Model;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.provider.DefaultModels;
import site.pplee.jcode.ai.provider.ModelProvider;
import site.pplee.jcode.ai.provider.ProviderAuth;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.app.SessionRegistry;
import site.pplee.jcode.codingagent.CodingAgentSessionOptions;
import site.pplee.jcode.codingagent.InputDeliveryMode;
import site.pplee.jcode.codingagent.settings.CodingAgentSettings;
import site.pplee.jcode.codingagent.settings.SettingsOverrides;
import site.pplee.jcode.codingagent.tool.BashConfig;
import site.pplee.jcode.codingagent.tool.CodingToolConfig;
import site.pplee.jcode.protocol.EventType;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/** Separate-JVM test service. No production route or model setting uses this provider. */
public final class TakeoverServerFixture {
    private static final ModelRef MODEL = new ModelRef("test", "takeover", "one");

    private TakeoverServerFixture() { }

    public static void main(String[] args) throws Exception {
        Path configFile = Path.of(args[0]);
        Path toolGate = Path.of(args[1]);
        Path observerReady = Path.of(args[2]);
        Path approvalReady = Path.of(args[3]);
        var config = ServerConfig.load(configFile, new ObjectMapper());
        var registry = new SessionRegistry();
        var provider = new GatedToolProvider(toolGate);
        try (var server = JcodeServer.start(config, registry, exchange -> { },
                base -> options(base, provider))) {
            Thread.startVirtualThread(() -> observeApproval(registry, provider,
                    observerReady, approvalReady));
            System.out.println("TAKEOVER_SERVER_READY endpoint=" + server.endpoint()
                    + " instanceId=" + server.instanceId()
                    + " pid=" + ProcessHandle.current().pid());
            System.out.flush();
            server.awaitTermination();
        }
    }

    private static CodingAgentSessionOptions options(
            CodingAgentSessionOptions base, ModelProvider provider
    ) {
        var bash = new BashConfig(Path.of("/bin/sh"), null,
                Duration.ofSeconds(10), Duration.ofSeconds(10));
        return CodingAgentSessionOptions.builder(base.workingDirectory())
                .borrowedModels(new DefaultModels(List.of(provider)), Map.of())
                .settingsOverrides(SettingsOverrides.settings(CodingAgentSettings.builder()
                        .defaultModel(MODEL).build()))
                .tools(CodingToolConfig.coding(bash))
                .inputDeliveryMode(InputDeliveryMode.RUN_SCOPED)
                .build();
    }

    private static void observeApproval(
            SessionRegistry registry, GatedToolProvider provider,
            Path observerReady, Path approvalReady
    ) {
        try {
            provider.firstRequest.await();
            var session = registry.managedSessions().getFirst();
            try (var subscription = session.subscribe(session.snapshot().cursor())) {
                Files.writeString(observerReady, "subscribed");
                boolean pendingSeen = false;
                boolean approvalFinished = false;
                boolean runFinished = false;
                while (true) {
                    var event = subscription.next(Duration.ofSeconds(30)).orElseThrow();
                    String status = event.data().path("status").asText();
                    if (event.type() == EventType.APPROVAL_CHANGED && !pendingSeen
                            && "PENDING".equals(status)) {
                        pendingSeen = true;
                        Files.writeString(approvalReady, "pending");
                    } else if (event.type() == EventType.APPROVAL_CHANGED && pendingSeen
                            && !"PENDING".equals(status)) {
                        approvalFinished = true;
                        Files.writeString(approvalReady.resolveSibling("approval.terminal"), status);
                    } else if (event.type() == EventType.RUN_CHANGED && pendingSeen
                            && ("COMPLETED".equals(status) || "FAILED".equals(status)
                                    || "CANCELLED".equals(status))) {
                        runFinished = true;
                        Files.writeString(approvalReady.resolveSibling("run.terminal"), status);
                    }
                    if (approvalFinished && runFinished) {
                        return;
                    }
                }
            }
        } catch (Exception failure) {
            failure.printStackTrace(System.err);
        }
    }

    private static void waitForFile(Path target) throws Exception {
        try (var watcher = FileSystems.getDefault().newWatchService()) {
            target.getParent().register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            while (!Files.exists(target)) {
                watcher.take().reset();
            }
        }
    }

    private static Message.Assistant answer(List<Content> content, StopReason reason) {
        return new Message.Assistant(content, reason, null, Usage.zero(), Instant.EPOCH, MODEL);
    }

    private static final class GatedToolProvider implements ModelProvider {
        private final Path toolGate;
        private final CountDownLatch firstRequest = new CountDownLatch(1);
        private int calls;

        private GatedToolProvider(Path toolGate) {
            this.toolGate = toolGate;
        }

        @Override public String id() { return "test"; }
        @Override public String name() { return "Takeover fixture"; }
        @Override public Optional<URI> baseUrl() { return Optional.empty(); }
        @Override public ProviderAuth auth() { return ProviderAuth.of(true, ""); }
        @Override public List<Model> models() {
            return List.of(new Model("test", "takeover", "one", "Takeover fixture"));
        }
        @Override public boolean supports(ModelRef ref) { return MODEL.equals(ref); }

        @Override
        public synchronized AssistantMessageStream stream(
                ModelRequest request, CancellationSignal cancellation
        ) {
            calls++;
            var stream = new AssistantMessageStream();
            if (calls == 1) {
                stream.push(new AssistantMessageEvent.Start(answer(List.of(), StopReason.STOP)));
                firstRequest.countDown();
                Thread.startVirtualThread(() -> {
                    try {
                        waitForFile(toolGate);
                        var arguments = new ObjectMapper().createObjectNode()
                                .put("command", "printf 'one execution\\n' >> effects.txt");
                        var tool = answer(List.of(new Content.ToolCall("tool-1", "bash", arguments)),
                                StopReason.TOOL_CALL);
                        stream.push(new AssistantMessageEvent.Done(StopReason.TOOL_CALL, tool));
                    } catch (Exception failure) {
                        failure.printStackTrace(System.err);
                    }
                });
            } else {
                var done = answer(List.of(new Content.Text("completed call " + calls)),
                        StopReason.STOP);
                stream.push(new AssistantMessageEvent.Start(done));
                stream.push(new AssistantMessageEvent.Done(StopReason.STOP, done));
            }
            return stream;
        }
    }
}
