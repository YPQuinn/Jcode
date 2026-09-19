package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.concurrent.CancellationRegistration;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;

import java.nio.ByteBuffer;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReadToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void preparesDefaultsAndRejectsCoercionAndUnknownFields() {
        var tool = new ReadTool(directory);
        var prepared = tool.prepareArguments(MAPPER.createObjectNode().put("path", "a.txt"));
        assertEquals(1, prepared.get("offset").intValue());
        assertEquals(2000, prepared.get("limit").intValue());
        assertFalse(tool.parametersSchema().get("additionalProperties").booleanValue());
        assertEquals(Integer.MAX_VALUE,
                tool.parametersSchema().path("properties").path("offset").path("maximum").intValue());

        assertThrows(IllegalArgumentException.class, () -> tool.prepareArguments(
                MAPPER.createObjectNode().put("path", "a").putNull("offset")));
        assertThrows(IllegalArgumentException.class, () -> tool.prepareArguments(
                MAPPER.createObjectNode().put("path", "a").put("offset", "1")));
        assertThrows(IllegalArgumentException.class, () -> tool.prepareArguments(
                MAPPER.createObjectNode().put("path", "a").put("limit", 1.5)));
        assertThrows(IllegalArgumentException.class, () -> tool.prepareArguments(
                MAPPER.createObjectNode().put("path", "a").put("offset", 0)));
        assertThrows(IllegalArgumentException.class, () -> tool.prepareArguments(
                MAPPER.createObjectNode().put("path", "a").put("limit", 2_147_483_648L)));
        assertThrows(IllegalArgumentException.class, () -> tool.prepareArguments(
                MAPPER.createObjectNode().put("path", "a").put("extra", true)));
        assertThrows(IllegalArgumentException.class, () -> tool.prepareArguments(
                JsonNodeFactory.instance.arrayNode()));
    }

    @Test
    void readsRelativeUtf8TextWithNormalizedLineEndings() throws Exception {
        Files.writeString(directory.resolve("notes.txt"), "alpha\r\nbeta\rgamma");
        var result = execute(new ReadTool(directory), new ReadToolArguments("notes.txt", 1, 2000));

        assertFalse(result.error());
        assertEquals("alpha\nbeta\ngamma", assertInstanceOf(Content.Text.class, result.content().getFirst()).text());
    }

    @Test
    void paginatesWithoutLosingOrRepeatingLines() throws Exception {
        Files.writeString(directory.resolve("large.txt"), "one\ntwo\nthree\n");
        var tool = new ReadTool(directory);

        var first = text(execute(tool, new ReadToolArguments("large.txt", 1, 2)));
        var second = text(execute(tool, new ReadToolArguments("large.txt", 3, 2)));

        assertTrue(first.startsWith("one\ntwo\n"));
        assertTrue(first.contains("offset=3"));
        assertEquals("three\n", second);
    }

    @Test
    void rejectsOverlongFirstLineInsteadOfReturningPartialSuccess() throws Exception {
        Files.writeString(directory.resolve("minified.txt"), "x".repeat(51 * 1024) + "\nnext\n");
        var result = execute(new ReadTool(directory), new ReadToolArguments("minified.txt", 1, 2000));

        assertTrue(result.error());
        assertTrue(text(result).contains("line 1"));
        assertFalse(text(result).contains("offset="));
    }

    @Test
    void returnsEmptyAndBeyondEndMessages() throws Exception {
        Files.writeString(directory.resolve("empty.txt"), "");
        Files.writeString(directory.resolve("short.txt"), "one\n");

        assertEquals("(empty file)", text(execute(
                new ReadTool(directory), new ReadToolArguments("empty.txt", 1, 2000))));
        assertEquals("(offset 3 is beyond end of file)", text(execute(
                new ReadTool(directory), new ReadToolArguments("short.txt", 3, 2000))));
    }

    @Test
    void readsSupportedImagesAfterExtensionAndMagicValidation() throws Exception {
        var png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        Files.write(directory.resolve("pixel.png"), png);

        var result = execute(new ReadTool(directory), new ReadToolArguments("pixel.png", 1, 2000));
        var image = assertInstanceOf(Content.Image.class, result.content().getFirst());
        assertEquals("image/png", image.mediaType());
        assertArrayEquals(png, Base64.getDecoder().decode(image.base64Data()));
        assertFalse(image.toString().contains(image.base64Data()));
    }

    @Test
    void readsJpegAndWebpWithCanonicalMediaTypes() throws Exception {
        var jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};
        var webp = new byte[] {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P', 1};
        Files.write(directory.resolve("photo.JPEG"), jpeg);
        Files.write(directory.resolve("shape.webp"), webp);

        var jpegResult = execute(new ReadTool(directory), new ReadToolArguments("photo.JPEG", 1, 1));
        var webpResult = execute(new ReadTool(directory), new ReadToolArguments("shape.webp", 1, 1));

        assertEquals("image/jpeg", assertInstanceOf(Content.Image.class,
                jpegResult.content().getFirst()).mediaType());
        assertEquals("image/webp", assertInstanceOf(Content.Image.class,
                webpResult.content().getFirst()).mediaType());
    }

    @Test
    void rejectsGifBmpMagicMismatchAndOversizeImageBeforeProducingImage() throws Exception {
        Files.write(directory.resolve("still.gif"), "GIF89a".getBytes());
        Files.write(directory.resolve("bitmap.bmp"), new byte[] {'B', 'M', 0, 0});
        Files.write(directory.resolve("fake.png"), "not png".getBytes());
        try (var channel = Files.newByteChannel(directory.resolve("huge.png"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(ReadTool.MAX_IMAGE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }

        assertTrue(execute(new ReadTool(directory), new ReadToolArguments("still.gif", 1, 1)).error());
        assertTrue(execute(new ReadTool(directory), new ReadToolArguments("bitmap.bmp", 1, 1)).error());
        assertTrue(execute(new ReadTool(directory), new ReadToolArguments("fake.png", 1, 1)).error());
        assertTrue(execute(new ReadTool(directory), new ReadToolArguments("huge.png", 1, 1)).error());
    }

    @Test
    void enforcesHardLineLimitAndRejectsUnsupportedBinaryContent() throws Exception {
        Files.writeString(directory.resolve("many.txt"), "line\n".repeat(ReadTool.MAX_TEXT_LINES + 1));
        Files.write(directory.resolve("binary.dat"), new byte[] {'a', 0, 'b'});

        var page = text(execute(new ReadTool(directory),
                new ReadToolArguments("many.txt", 1, Integer.MAX_VALUE)));
        assertTrue(page.contains("line limit reached"));
        assertTrue(page.contains("offset=2001"));
        assertTrue(execute(new ReadTool(directory),
                new ReadToolArguments("binary.dat", 1, 1)).error());
    }

    @Test
    void observesCancellationDuringLargeTextScan() throws Exception {
        Files.writeString(directory.resolve("large.txt"), "x".repeat(10_000));
        var checks = new AtomicInteger();
        CancellationSignal cancellation = new CancellationSignal() {
            @Override
            public boolean isCancelled() {
                return checks.get() >= 3;
            }

            @Override
            public void throwIfCancelled() {
                if (checks.incrementAndGet() >= 3) {
                    throw new CancellationException("cancelled");
                }
            }

            @Override
            public CancellationRegistration onCancellation(Runnable listener) {
                return () -> { };
            }
        };

        var result = new ReadTool(directory).execute(
                "id", new ReadToolArguments("large.txt", 1, 1), ToolUpdateSink.noop(), cancellation)
                .toCompletableFuture().join();

        assertTrue(result.error());
        assertTrue(text(result).contains("cancel"));
    }

    @Test
    void rejectsMissingDirectoryMalformedUtf8AndCancellation() throws Exception {
        Files.write(directory.resolve("bad.txt"), new byte[] {(byte) 0xc3, 0x28});
        var tool = new ReadTool(directory);

        assertTrue(execute(tool, new ReadToolArguments("missing.txt", 1, 1)).error());
        assertTrue(execute(tool, new ReadToolArguments(".", 1, 1)).error());
        assertTrue(execute(tool, new ReadToolArguments("bad.txt", 1, 1)).error());

        var cancellation = new MutableCancellationSignal();
        cancellation.cancel();
        var cancelled = tool.execute("id", new ReadToolArguments("bad.txt", 1, 1),
                ToolUpdateSink.noop(), cancellation).toCompletableFuture().join();
        assertTrue(cancelled.error());
        assertTrue(text(cancelled).contains("cancel"));
    }

    @Test
    void argumentRecordRejectsInvalidDirectConstruction() {
        assertThrows(NullPointerException.class, () -> new ReadToolArguments(null, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReadToolArguments("x", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReadToolArguments("x", 1, -1));
        assertEquals(1, new ReadToolArguments("x", null, null).offset());
        assertEquals(2000, new ReadToolArguments("x", null, null).limit());
    }

    @Test
    void resolvesParentAfterSymlinkForRelativeAndAbsolutePaths() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Path external = Files.createDirectory(directory.resolve("external"));
        Path target = Files.createDirectory(external.resolve("target"));
        try {
            Files.createSymbolicLink(workspace.resolve("link"), target);
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.abort("symbolic links are unavailable: " + e.getMessage());
        }
        Files.writeString(workspace.resolve("answer.txt"), "wrong file");
        Files.writeString(external.resolve("answer.txt"), "expected file");
        var tool = new ReadTool(workspace);
        Path relative = Path.of("link", "..", "answer.txt");

        for (Path path : List.of(relative, workspace.resolve(relative))) {
            var result = execute(tool, new ReadToolArguments(path.toString(), null, null));
            assertFalse(result.error());
            assertEquals(Files.readString(workspace.resolve(relative)), text(result));
        }
    }

    @Test
    void preservesFilesystemResolutionForMissingOrRegularFileParentSegments() throws Exception {
        Files.writeString(directory.resolve("answer.txt"), "answer");
        Files.writeString(directory.resolve("regular.txt"), "not a directory");
        var tool = new ReadTool(directory);

        for (String prefix : List.of("missing", "regular.txt")) {
            Path relative = Path.of(prefix, "..", "answer.txt");
            for (Path path : List.of(relative, directory.resolve(relative))) {
                var result = execute(tool, new ReadToolArguments(path.toString(), null, null));
                Path nativePath = directory.resolve(relative);
                assertEquals(!Files.isRegularFile(nativePath), result.error(), path.toString());
                if (!result.error()) {
                    assertEquals(Files.readString(nativePath), text(result));
                }
            }
        }
    }

    private static site.pplee.jcode.agentcore.tool.ToolExecutionResult execute(
            ReadTool tool, ReadToolArguments arguments) {
        return tool.execute("call", arguments, ToolUpdateSink.noop(), new MutableCancellationSignal())
                .toCompletableFuture().join();
    }

    private static String text(site.pplee.jcode.agentcore.tool.ToolExecutionResult result) {
        return assertInstanceOf(Content.Text.class, result.content().getFirst()).text();
    }
}
