package site.pplee.jcode.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import site.pplee.jcode.app.ApiException;
import site.pplee.jcode.app.ManagedSession;
import site.pplee.jcode.protocol.ErrorCode;
import site.pplee.jcode.protocol.EventCursor;
import site.pplee.jcode.protocol.SessionEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Serializes the existing bounded subscription into reconnectable SSE frames. */
final class SseHandler {
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final ObjectMapper mapper;

    SseHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void stream(HttpExchange exchange, ManagedSession session) throws IOException {
        EventCursor after = cursor(exchange);
        try (var subscription = session.subscribe(after)) {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            while (true) {
                try {
                    var next = subscription.next(HEARTBEAT_INTERVAL);
                    if (next.isPresent()) {
                        event(body, next.orElseThrow());
                    } else {
                        write(body, ": heartbeat\n\n");
                    }
                } catch (ApiException closed) {
                    if (closed.error().code() == ErrorCode.SUBSCRIBER_SLOW) {
                        control(body, ErrorCode.SUBSCRIBER_SLOW, "resync");
                    } else if (closed.error().code() == ErrorCode.SESSION_CLOSED) {
                        control(body, ErrorCode.SESSION_CLOSED, "stop");
                    }
                    return;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private EventCursor cursor(HttpExchange exchange) {
        String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        String encoded;
        if (lastEventId != null && !lastEventId.isBlank()) {
            encoded = lastEventId;
        } else {
            Map<String, String> query = HttpApi.query(exchange);
            if (query.size() != 1 || !query.containsKey("after")) {
                throw new ApiException(ErrorCode.INVALID_ARGUMENT,
                        "take a snapshot and provide its cursor as after");
            }
            encoded = query.get("after");
        }
        int separator = encoded.lastIndexOf(':');
        if (separator <= 0 || separator == encoded.length() - 1) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "invalid event cursor");
        }
        try {
            return new EventCursor(encoded.substring(0, separator),
                    Long.parseLong(encoded.substring(separator + 1)));
        } catch (IllegalArgumentException failure) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "invalid event cursor");
        }
    }

    private void event(OutputStream body, SessionEvent event) throws IOException {
        String id = event.cursor().epoch() + ":" + event.cursor().seq();
        write(body, "id: " + id + "\nevent: session.event\ndata: "
                + mapper.writeValueAsString(event) + "\n\n");
    }

    private void control(OutputStream body, ErrorCode code, String action) throws IOException {
        write(body, "event: stream.control\ndata: "
                + mapper.writeValueAsString(new StreamControl(code, action)) + "\n\n");
    }

    private static void write(OutputStream body, String frame) throws IOException {
        body.write(frame.getBytes(StandardCharsets.UTF_8));
        body.flush();
    }

    private record StreamControl(ErrorCode code, String action) { }
}
