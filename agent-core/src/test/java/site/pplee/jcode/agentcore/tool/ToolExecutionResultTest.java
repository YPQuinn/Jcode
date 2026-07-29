package site.pplee.jcode.agentcore.tool;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.message.Content;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionResultTest {
    @Test
    void successHasNoErrorAndDoesNotTerminate() {
        var result = ToolExecutionResult.success(List.of(new Content.Text("ok")));

        assertFalse(result.error());
        assertFalse(result.terminate());
        assertEquals(List.of(new Content.Text("ok")), result.content());
    }

    @Test
    void successCanTerminate() {
        var result = ToolExecutionResult.success(List.of(new Content.Text("done")), true);

        assertFalse(result.error());
        assertTrue(result.terminate());
    }

    @Test
    void failureSetsErrorAndWrapsMessageAsText() {
        var result = ToolExecutionResult.failure("boom");

        assertTrue(result.error());
        assertFalse(result.terminate());
        assertEquals(List.of(new Content.Text("boom")), result.content());
    }

    @Test
    void failureRejectsNullMessage() {
        assertThrows(NullPointerException.class, () -> ToolExecutionResult.failure(null));
    }

    @Test
    void contentIsCopiedAndImmutable() {
        var original = new ArrayList<Content>();
        original.add(new Content.Text("a"));

        var result = ToolExecutionResult.success(original);
        original.add(new Content.Text("b"));

        assertEquals(List.of(new Content.Text("a")), result.content());
        assertThrows(UnsupportedOperationException.class,
                () -> result.content().add(new Content.Text("c")));
    }

    @Test
    void constructorRejectsNullContent() {
        assertThrows(NullPointerException.class, () -> new ToolExecutionResult(null, false, false));
    }
}
