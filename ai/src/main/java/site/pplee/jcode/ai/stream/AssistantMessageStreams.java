package site.pplee.jcode.ai.stream;

import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.message.Usage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Factory for well-formed failure streams. Synthetic failures (unknown
 * provider, unsupported model, missing auth, adapter errors) must follow the
 * same lifecycle as real streams: an initial {@link AssistantMessageEvent.Start}
 * carrying the partial content, then a terminal
 * {@link AssistantMessageEvent.Error}. The result stage completes normally
 * with the failure message — never exceptionally.
 */
public final class AssistantMessageStreams {
    private AssistantMessageStreams() {
    }

    /** A failure stream with empty partial content. */
    public static AssistantMessageStream failed(StopReason reason, String errorMessage) {
        return failed(reason, errorMessage, List.of());
    }

    /** A failure stream carrying the given partial content. */
    public static AssistantMessageStream failed(
            StopReason reason, String errorMessage, List<Content> partialContent
    ) {
        Objects.requireNonNull(errorMessage, "errorMessage must not be null");
        if (!reason.isTerminalFailure()) {
            throw new IllegalArgumentException("failed stream reason must be ERROR or ABORTED");
        }
        var content = List.copyOf(partialContent);
        var stream = new AssistantMessageStream();
        var start = new Message.Assistant(content, StopReason.STOP, null, Usage.zero(), Instant.now());
        var error = new Message.Assistant(content, reason, errorMessage, Usage.zero(), Instant.now());
        stream.push(new AssistantMessageEvent.Start(start));
        stream.push(new AssistantMessageEvent.Error(reason, error));
        return stream;
    }
}
