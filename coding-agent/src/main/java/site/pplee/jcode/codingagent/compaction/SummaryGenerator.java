package site.pplee.jcode.codingagent.compaction;

import site.pplee.jcode.ai.client.ModelClient;
import site.pplee.jcode.ai.client.ModelRequest;
import site.pplee.jcode.ai.client.ModelRequestOptions;
import site.pplee.jcode.ai.client.PromptCacheOptions;
import site.pplee.jcode.ai.client.ToolChoice;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;

import java.time.Instant;
import java.util.List;

/** Executes and validates one dedicated no-tools summary model request. */
public final class SummaryGenerator {
    public static final String SYSTEM_PROMPT = """
            Summarize the supplied conversation material for a coding agent that must continue the work.
            Preserve the goal, constraints, completed and in-progress work, blockers, key decisions,
            errors, unverified assumptions, next steps, and important file paths. Distinguish attempted
            work from confirmed success. If a previous summary is present, update it with the newer
            material. Return only the durable summary and do not request or call tools.
            """;

    private final ModelClient client;

    public SummaryGenerator(ModelClient client) {
        this.client = client;
    }

    public GeneratedSummary generate(
            ModelRef model,
            ThinkingLevel thinking,
            int maxOutputTokens,
            String material,
            CancellationSignal cancellation
    ) {
        var request = new ModelRequest(
                model,
                SYSTEM_PROMPT,
                List.of(new Message.User(List.of(new Content.Text(material)), Instant.now())),
                List.of(),
                thinking,
                new ModelRequestOptions(
                        maxOutputTokens, null, ToolChoice.Mode.AUTO, PromptCacheOptions.none()));
        var stream = client.stream(request, cancellation);
        Message.Assistant terminal = null;
        try {
            while (true) {
                var event = stream.take();
                if (event == null) {
                    break;
                }
                if (event instanceof site.pplee.jcode.ai.stream.AssistantMessageEvent.Done done) {
                    terminal = done.message();
                    break;
                }
                if (event instanceof site.pplee.jcode.ai.stream.AssistantMessageEvent.Error error) {
                    terminal = error.error();
                    break;
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("summary generation was interrupted", failure);
        }
        if (terminal == null) {
            throw new IllegalStateException("summary stream ended without a terminal result");
        }
        if (terminal.stopReason() != StopReason.STOP) {
            throw new IllegalStateException("summary model did not stop normally: " + terminal.stopReason());
        }
        if (terminal.content().stream().anyMatch(Content.ToolCall.class::isInstance)) {
            throw new IllegalStateException("summary model returned a tool call");
        }
        String text = terminal.content().stream()
                .filter(Content.Text.class::isInstance)
                .map(Content.Text.class::cast)
                .map(Content.Text::text)
                .reduce("", (left, right) -> left + right)
                .strip();
        if (text.isEmpty()) {
            throw new IllegalStateException("summary model returned empty text");
        }
        return new GeneratedSummary(text, terminal.usage());
    }

    public record GeneratedSummary(String text, site.pplee.jcode.ai.message.Usage usage) {
    }
}
