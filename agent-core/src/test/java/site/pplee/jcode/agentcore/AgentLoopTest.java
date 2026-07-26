package site.pplee.jcode.agentcore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.agentcore.concurrent.CancellationSource;
import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.agentcore.model.AgentContext;
import site.pplee.jcode.agentcore.model.AgentMessage;
import site.pplee.jcode.agentcore.model.Content;
import site.pplee.jcode.agentcore.model.LoopResult;
import site.pplee.jcode.agentcore.model.ModelRef;
import site.pplee.jcode.agentcore.model.StopReason;
import site.pplee.jcode.agentcore.support.RecordingEventSink;
import site.pplee.jcode.agentcore.support.ScriptedLlmClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class AgentLoopTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ModelRef MODEL = new ModelRef("test", "test-model");

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void singleModelAnswerEndsLoop() {
        // Given: a scripted STOP assistant, a recording sink, and an empty context.
        var assistant = new AgentMessage.Assistant(
                List.of(new Content.Text("hello")), StopReason.STOP, null, T1);
        var llmClient = new ScriptedLlmClient(assistant);
        var eventSink = new RecordingEventSink();
        var config = new AgentLoopConfig(
                MODEL, llmClient, new ObjectMapper(),
                null, null, null, eventSink, null);
        var context = new AgentContext("sys", List.of(), List.of());
        var prompt = new AgentMessage.User(List.of(new Content.Text("hi")), T1);
        var loop = new AgentLoop(executor);

        // When: prompt the loop with a single user message.
        LoopResult result = loop.runPrompt(
                List.of(prompt), context, config, new CancellationSource().token());

        // Then: the model was called exactly once.
        assertEquals(1, llmClient.receivedRequests().size());

        // And: the final context holds [user, assistant] in order, by reference.
        var messages = result.context().messages();
        assertEquals(2, messages.size());
        assertSame(prompt, messages.get(0));
        assertSame(assistant, messages.get(1));
        assertEquals(2, result.newMessages().size());

        // And: the event lifecycle is AgentStarted, TurnStarted, MessageCompleted(user),
        //      MessageCompleted(assistant), TurnCompleted, AgentCompleted.
        var events = eventSink.events();
        assertEquals(6, events.size());
        assertInstanceOf(AgentEvent.AgentStarted.class, events.get(0));
        assertInstanceOf(AgentEvent.TurnStarted.class, events.get(1));

        var userCompleted = (AgentEvent.MessageCompleted) events.get(2);
        assertSame(prompt, userCompleted.message());

        var assistantCompleted = (AgentEvent.MessageCompleted) events.get(3);
        assertSame(assistant, assistantCompleted.message());

        var turnCompleted = (AgentEvent.TurnCompleted) events.get(4);
        assertSame(assistant, turnCompleted.assistant());
        assertEquals(List.of(), turnCompleted.toolResults());

        var agentCompleted = (AgentEvent.AgentCompleted) events.get(5);
        assertSame(result, agentCompleted.result());
    }
}
