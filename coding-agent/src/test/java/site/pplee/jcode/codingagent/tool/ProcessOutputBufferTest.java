package site.pplee.jcode.codingagent.tool;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ProcessOutputBufferTest {
    @Test
    void decodesUtf8AcrossArbitraryChunkBoundaries() {
        byte[] bytes = "start-你好-end".getBytes(StandardCharsets.UTF_8);
        var buffer = new ProcessOutputBuffer(20, 1024);

        for (byte value : bytes) {
            buffer.append(new byte[]{value}, 0, 1);
        }
        buffer.finish();

        var snapshot = buffer.snapshot();
        assertEquals("start-你好-end", snapshot.content());
        assertFalse(snapshot.truncated());
        assertFalse(snapshot.invalidUtf8());
    }

    @Test
    void replacesInvalidUtf8WithoutFailingTheCollector() {
        var buffer = new ProcessOutputBuffer(20, 1024);

        buffer.append(new byte[]{'a', (byte) 0xc3, '(', 'b'}, 0, 4);
        buffer.finish();

        var snapshot = buffer.snapshot();
        assertEquals("a�(b", snapshot.content());
        assertTrue(snapshot.invalidUtf8());
    }

    @Test
    void keepsTheLastCompleteLinesWhenLineLimitIsReached() {
        var buffer = new ProcessOutputBuffer(2, 1024);
        byte[] output = "one\ntwo\nthree\nfour\n".getBytes(StandardCharsets.UTF_8);

        buffer.append(output, 0, output.length);
        buffer.finish();

        var snapshot = buffer.snapshot();
        assertEquals("three\nfour\n", snapshot.content());
        assertEquals(4, snapshot.totalLines());
        assertEquals(2, snapshot.outputLines());
        assertTrue(snapshot.truncated());
        assertEquals(ProcessOutputSnapshot.TruncatedBy.LINES, snapshot.truncatedBy());
        assertFalse(snapshot.firstLinePartial());
    }

    @Test
    void keepsAValidUtf8SuffixOfOneHugeLine() {
        var buffer = new ProcessOutputBuffer(20, 10);
        byte[] output = ("prefix-" + "你".repeat(20)).getBytes(StandardCharsets.UTF_8);

        buffer.append(output, 0, output.length);
        buffer.finish();

        var snapshot = buffer.snapshot();
        assertTrue(snapshot.truncated());
        assertEquals(ProcessOutputSnapshot.TruncatedBy.BYTES, snapshot.truncatedBy());
        assertTrue(snapshot.firstLinePartial());
        assertTrue(snapshot.content().endsWith("你你你"));
        assertTrue(snapshot.content().getBytes(StandardCharsets.UTF_8).length <= 10);
        assertFalse(snapshot.content().contains("�"));
    }

    @Test
    void snapshotsRemainBoundedWhileManyChunksArrive() {
        var buffer = new ProcessOutputBuffer(20, 128);
        byte[] chunk = "0123456789abcdef\n".repeat(100).getBytes(StandardCharsets.UTF_8);

        for (int index = 0; index < 100; index++) {
            buffer.append(chunk, 0, chunk.length);
            assertTrue(buffer.retainedUtf8Bytes() <= 256);
            assertTrue(buffer.snapshot().content().getBytes(StandardCharsets.UTF_8).length <= 128);
        }
        buffer.finish();

        assertTrue(buffer.snapshot().truncated());
    }

    @Test
    void finishIsIdempotentAndAppendAfterFinishIsRejected() {
        var buffer = new ProcessOutputBuffer(20, 128);
        buffer.finish();
        buffer.finish();

        assertThrows(IllegalStateException.class,
                () -> buffer.append(new byte[]{'x'}, 0, 1));
    }
}
