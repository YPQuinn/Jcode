package site.pplee.jcode.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.stream.AssistantMessageEvent;
import site.pplee.jcode.ai.stream.AssistantMessageStream;
import site.pplee.jcode.codingagent.CodingAgentConfig;
import site.pplee.jcode.codingagent.InputDeliveryMode;
import site.pplee.jcode.protocol.RunCommand;
import site.pplee.jcode.protocol.RunKind;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Separate-JVM test owner for a real socket whose peer stops reading SSE. */
public final class ServerTimeoutFixture {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "one");

    private ServerTimeoutFixture() { }

    public static void main(String[] args) throws Exception {
        Path configFile = Path.of(args[0]);
        Path trigger = Path.of(args[1]);
        Path done = Path.of(args[2]);
        var config = ServerConfig.load(configFile, new ObjectMapper());
        Path workspace = config.workspaces().get("project");
        try (var server = JcodeServer.start(config)) {
            var managed = server.sessions().create(new CodingAgentConfig(
                    workspace, MODEL, new LargeReply(), new ObjectMapper(),
                    null, null, null, null, null, null, null, null,
                    null, null, null, Map.of(), null, InputDeliveryMode.RUN_SCOPED),
                    server.sessionDirectory("project"));
            var cursor = managed.snapshot().cursor();
            try (var watch = FileSystems.getDefault().newWatchService()) {
                trigger.getParent().register(watch, StandardWatchEventKinds.ENTRY_CREATE);
                System.out.println("TEST_SERVER_READY endpoint=" + server.endpoint()
                        + " sessionId=" + managed.sessionId()
                        + " after=" + cursor.epoch() + ":" + cursor.seq());
                System.out.flush();
                while (!Files.exists(trigger)) {
                    watch.take().reset();
                }
            }
            for (int index = 0; index < 300; index++) {
                String id = Integer.toString(index);
                managed.start(new RunCommand("cmd-" + id, "run-" + id,
                        RunKind.PROMPT, "flood", null));
                managed.settled("run-" + id).toCompletableFuture().join();
            }
            Files.writeString(done, "finished");
            server.awaitTermination();
        }
    }

    private static final class LargeReply implements ModelClient {
        private static final String TEXT = "x".repeat(8_192);

        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            var answer = new Message.Assistant(List.of(new Content.Text(TEXT)),
                    StopReason.STOP, null, Usage.zero(), Instant.EPOCH, MODEL);
            var stream = new AssistantMessageStream();
            stream.push(new AssistantMessageEvent.Start(answer));
            stream.push(new AssistantMessageEvent.Done(StopReason.STOP, answer));
            return stream;
        }
    }
}
