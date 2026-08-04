package site.pplee.jcode.aiproviders.openai;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * Minimal Server-Sent Events parser: reads lines, accumulates {@code event:}
 * and {@code data:} fields, and dispatches each complete event (terminated by
 * a blank line) to {@link Handler#onEvent(String, String)}. Comments are
 * ignored; multi-line data values are joined with {@code \n}; a pending event
 * is flushed at EOF. The {@code [DONE]} marker is delivered as a regular data
 * value so the caller decides its meaning.
 */
final class OpenAiSseParser {

    /** Receives one complete SSE event. */
    interface Handler {
        void onEvent(String eventName, String data);
    }

    void parse(BufferedReader reader, Handler handler) throws IOException {
        String line;
        String eventName = "message";
        StringBuilder data = new StringBuilder();
        boolean haveData = false;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (haveData) {
                    handler.onEvent(eventName, data.toString());
                    eventName = "message";
                    data.setLength(0);
                    haveData = false;
                }
            } else if (line.charAt(0) == ':') {
                // Comment / heartbeat — ignore.
            } else {
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                switch (field) {
                    case "event" -> eventName = value;
                    case "data" -> {
                        if (haveData) {
                            data.append('\n');
                        }
                        data.append(value);
                        haveData = true;
                    }
                    default -> {
                        // Ignore id/retry and unknown fields.
                    }
                }
            }
        }
        if (haveData) {
            handler.onEvent(eventName, data.toString());
        }
    }
}
