package site.pplee.jcode.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import site.pplee.jcode.app.SessionRegistry;
import site.pplee.jcode.codingagent.CodingAgentConfig;
import site.pplee.jcode.codingagent.InputDeliveryMode;
import site.pplee.jcode.protocol.RunCommand;
import site.pplee.jcode.protocol.RunKind;
import site.pplee.jcode.protocol.RunStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SseHandlerTest {
    private static final ModelRef MODEL = new ModelRef("test", "scripted", "one");

    @TempDir
    Path directory;

    @Test
    void blockedStreamReportsResyncWithoutCancellingRuns() throws Exception {
        var registry = new SessionRegistry();
        var managed = registry.create(new CodingAgentConfig(
                directory, MODEL, new ImmediateModel(), new ObjectMapper(),
                null, null, null, null, null, null, null, null,
                null, null, null, Map.of(), null, InputDeliveryMode.RUN_SCOPED),
                directory.resolve("sessions"));
        var output = new PausedOutput();
        var cursor = managed.snapshot().cursor();
        var exchange = new Exchange(URI.create("/v1/sessions/" + managed.sessionId()
                + "/events?after=" + cursor.epoch() + ":" + cursor.seq()), output);
        var streaming = CompletableFuture.runAsync(() -> {
            try {
                new SseHandler(new ObjectMapper()).stream(exchange, managed);
            } catch (IOException failure) {
                throw new RuntimeException(failure);
            }
        });
        try {
            managed.start(new RunCommand("cmd-0", "run-0", RunKind.PROMPT, "first", null));
            assertTrue(output.writing.await(5, TimeUnit.SECONDS));
            managed.settled("run-0").toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            for (int index = 1; index < 80; index++) {
                String id = Integer.toString(index);
                managed.start(new RunCommand("cmd-" + id, "run-" + id,
                        RunKind.PROMPT, "next", null));
                managed.settled("run-" + id).toCompletableFuture()
                        .orTimeout(5, TimeUnit.SECONDS).join();
            }
            output.release.countDown();
            streaming.orTimeout(5, TimeUnit.SECONDS).join();
            String wire = output.text();
            int control = wire.indexOf("event: stream.control");
            assertTrue(control >= 0, wire);
            assertTrue(wire.substring(control).contains("SUBSCRIBER_SLOW"));
            assertFalse(wire.substring(control).contains("id: "));
            assertEquals(RunStatus.COMPLETED, managed.view("run-79").orElseThrow().status());
        } finally {
            output.release.countDown();
            registry.close(managed.sessionId());
        }
    }

    private static final class ImmediateModel implements ModelClient {
        @Override
        public AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation) {
            var answer = new Message.Assistant(List.of(new Content.Text("done")),
                    StopReason.STOP, null, Usage.zero(), Instant.EPOCH, MODEL);
            var stream = new AssistantMessageStream();
            stream.push(new AssistantMessageEvent.Start(answer));
            stream.push(new AssistantMessageEvent.Done(StopReason.STOP, answer));
            return stream;
        }
    }

    private static final class PausedOutput extends OutputStream {
        private final CountDownLatch writing = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean paused;

        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            if (!paused) {
                paused = true;
                writing.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            bytes.write(value, offset, length);
        }

        private String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class Exchange extends HttpExchange {
        private final URI uri;
        private final OutputStream output;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private int responseCode = -1;

        private Exchange(URI uri, OutputStream output) {
            this.uri = uri;
            this.output = output;
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        @Override public OutputStream getResponseBody() { return output; }
        @Override public void sendResponseHeaders(int status, long length) {
            responseCode = status;
        }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
