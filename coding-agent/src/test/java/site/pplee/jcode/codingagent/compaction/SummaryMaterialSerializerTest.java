package site.pplee.jcode.codingagent.compaction;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryMaterialSerializerTest {
    @Test
    void toolResultLimitCountsUnicodeCodePoints() {
        String withinLimit = "😀".repeat(1_500);
        String serializedWithinLimit = serializeToolResult(withinLimit);

        assertTrue(serializedWithinLimit.contains(withinLimit));
        assertFalse(serializedWithinLimit.contains("[tool result truncated for summary input]"));

        String overLimit = "😀".repeat(2_001);
        String serializedOverLimit = serializeToolResult(overLimit);

        assertTrue(serializedOverLimit.contains("😀".repeat(2_000)));
        assertTrue(serializedOverLimit.contains("[tool result truncated for summary input]"));
        assertFalse(serializedOverLimit.contains("😀".repeat(2_001)));
    }

    private static String serializeToolResult(String text) {
        var message = new Message.ToolResultMessage(
                "call-1", "read", List.of(new Content.Text(text)), false, Instant.EPOCH);
        return new SummaryMaterialSerializer().serialize(
                List.of(StandardAgentMessage.of(message)), null);
    }
}
