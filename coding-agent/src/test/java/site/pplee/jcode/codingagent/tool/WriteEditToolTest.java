package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.agentcore.tool.ToolExecutionMode;
import site.pplee.jcode.agentcore.tool.ToolExecutionResult;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;

import java.nio.ByteBuffer;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.*;

class WriteEditToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void declaresStrictArgumentsAndSequentialExecution() {
        var write = new WriteTool(directory);
        var edit = new EditTool(directory);

        assertEquals(ToolExecutionMode.SEQUENTIAL, write.executionMode());
        assertEquals(ToolExecutionMode.SEQUENTIAL, edit.executionMode());
        assertFalse(write.parametersSchema().path("additionalProperties").booleanValue());
        assertFalse(edit.parametersSchema().path("additionalProperties").booleanValue());
        assertEquals(100, edit.parametersSchema().path("properties").path("edits")
                .path("maxItems").intValue());

        assertThrows(IllegalArgumentException.class, () -> write.prepareArguments(
                MAPPER.createObjectNode().put("path", "a").put("content", "x").put("extra", 1)));
        assertThrows(IllegalArgumentException.class, () -> write.prepareArguments(
                MAPPER.createObjectNode().put("path", "a").putNull("content")));
        assertThrows(IllegalArgumentException.class, () -> edit.prepareArguments(
                MAPPER.createObjectNode().put("path", "a").putArray("edits")));
        assertThrows(IllegalArgumentException.class, () -> edit.prepareArguments(
                MAPPER.createObjectNode().put("path", "a").set("edits",
                        MAPPER.createObjectNode().put("oldText", "a").put("newText", "b"))));
        assertThrows(IllegalArgumentException.class, () -> edit.prepareArguments(
                JsonNodeFactory.instance.arrayNode()));
    }

    @Test
    void writesNewNestedFileAndAtomicallyOverwritesExistingFile() throws Exception {
        var tool = new WriteTool(directory);

        var created = execute(tool, new WriteToolArguments("nested/source.txt", "hello\n"));
        var overwritten = execute(tool, new WriteToolArguments("nested/source.txt", "updated\n"));

        assertFalse(created.error());
        assertFalse(overwritten.error());
        assertEquals("updated\n", Files.readString(directory.resolve("nested/source.txt")));
        assertTrue(text(overwritten).contains("8 bytes"));
        assertNoMutationTempFiles(directory.resolve("nested"));
    }

    @Test
    void rejectsMalformedUtf16OversizedContentAndOversizedExistingFileWithoutChangingTarget()
            throws Exception {
        Path target = directory.resolve("target.txt");
        Files.writeString(target, "original");
        var tool = new WriteTool(directory);

        var malformed = execute(tool, new WriteToolArguments("target.txt", "bad\ud800text"));
        var oversized = execute(tool, new WriteToolArguments(
                "target.txt", "x".repeat(FileMutationWriter.MAX_FILE_BYTES + 1)));
        Path huge = directory.resolve("huge.txt");
        try (var channel = Files.newByteChannel(huge, CREATE, WRITE)) {
            channel.position(FileMutationWriter.MAX_FILE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }
        var oversizedExisting = execute(tool, new WriteToolArguments("huge.txt", "small"));

        assertTrue(malformed.error());
        assertTrue(oversized.error());
        assertTrue(oversizedExisting.error());
        assertEquals("original", Files.readString(target));
        assertEquals(FileMutationWriter.MAX_FILE_BYTES + 1L, Files.size(huge));
    }

    @Test
    void preservesPosixPermissionsWhenReplacingExistingFile() throws Exception {
        Path target = directory.resolve("script.sh");
        Files.writeString(target, "old");
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-x---"));
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("POSIX permissions are unavailable");
        }
        Set<PosixFilePermission> expected = Files.getPosixFilePermissions(target);

        var result = execute(new WriteTool(directory),
                new WriteToolArguments("script.sh", "#!/bin/sh\nexit 0\n"));

        assertFalse(result.error());
        assertEquals(expected, Files.getPosixFilePermissions(target));
    }

    @Test
    void modifiesSymlinkReferentWithoutReplacingLink() throws Exception {
        Path referent = directory.resolve("referent.txt");
        Path link = directory.resolve("link.txt");
        Files.writeString(referent, "before\n");
        try {
            Files.createSymbolicLink(link, referent.getFileName());
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.abort("symbolic links are unavailable: " + e.getMessage());
        }

        var write = execute(new WriteTool(directory),
                new WriteToolArguments("link.txt", "written\n"));
        var edit = execute(new EditTool(directory), new EditToolArguments("link.txt", List.of(
                new EditReplacement("written", "edited"))));

        assertFalse(write.error());
        assertFalse(edit.error());
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("edited\n", Files.readString(referent));
    }

    @Test
    void preservesNativeResolutionOfParentAfterSymlink() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Path external = Files.createDirectory(directory.resolve("external"));
        Path target = Files.createDirectory(external.resolve("target"));
        try {
            Files.createSymbolicLink(workspace.resolve("link"), target);
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.abort("symbolic links are unavailable: " + e.getMessage());
        }
        Files.writeString(workspace.resolve("answer.txt"), "workspace");
        Files.writeString(external.resolve("answer.txt"), "external");

        var result = execute(new WriteTool(workspace),
                new WriteToolArguments(Path.of("link", "..", "answer.txt").toString(), "changed"));

        assertFalse(result.error());
        assertEquals("workspace", Files.readString(workspace.resolve("answer.txt")));
        assertEquals("changed", Files.readString(external.resolve("answer.txt")));
    }

    @Test
    void appliesEditAndLeavesFileUntouchedForNoChange() throws Exception {
        Path target = directory.resolve("source.txt");
        Files.writeString(target, "alpha\nbeta\ngamma\n");
        var tool = new EditTool(directory);

        var changed = execute(tool, new EditToolArguments("source.txt", List.of(
                new EditReplacement("alpha", "A"),
                new EditReplacement("gamma", "G"))));
        FileTime marker = FileTime.fromMillis(1_000_000);
        Files.setLastModifiedTime(target, marker);
        var unchanged = execute(tool, new EditToolArguments("source.txt", List.of(
                new EditReplacement("beta", "beta"))));

        assertFalse(changed.error());
        assertTrue(text(changed).contains("2 replacements"));
        assertFalse(unchanged.error());
        assertTrue(text(unchanged).contains("No changes"));
        assertEquals(marker, Files.getLastModifiedTime(target));
        assertEquals("A\nbeta\nG\n", Files.readString(target));
    }

    @Test
    void editValidationFailuresNeverPartiallyModifyFile() throws Exception {
        Path target = directory.resolve("source.txt");
        Files.writeString(target, "alpha alpha beta");
        var tool = new EditTool(directory);

        var duplicate = execute(tool, new EditToolArguments("source.txt", List.of(
                new EditReplacement("alpha", "A"),
                new EditReplacement("beta", "B"))));
        var missing = execute(tool, new EditToolArguments("source.txt", List.of(
                new EditReplacement("missing", "M"))));

        assertTrue(duplicate.error());
        assertTrue(missing.error());
        assertEquals("alpha alpha beta", Files.readString(target));
        assertNoMutationTempFiles(directory);
    }

    @Test
    void rejectsMissingBrokenLinkAndNonRegularTargets() throws Exception {
        var edit = new EditTool(directory);
        var write = new WriteTool(directory);
        Path broken = directory.resolve("broken.txt");
        try {
            Files.createSymbolicLink(broken, Path.of("missing-target"));
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.abort("symbolic links are unavailable: " + e.getMessage());
        }

        assertTrue(execute(edit, new EditToolArguments("missing.txt", List.of(
                new EditReplacement("a", "b")))).error());
        assertTrue(execute(edit, new EditToolArguments(".", List.of(
                new EditReplacement("a", "b")))).error());
        assertTrue(execute(write, new WriteToolArguments(".", "content")).error());
        assertTrue(execute(write, new WriteToolArguments("broken.txt", "content")).error());
        assertTrue(Files.isSymbolicLink(broken));
    }

    @Test
    void cancellationBeforeCommitPreservesExistingFile() throws Exception {
        Path target = directory.resolve("source.txt");
        Files.writeString(target, "original");
        var cancellation = new MutableCancellationSignal();
        cancellation.cancel();

        var write = new WriteTool(directory).execute(
                "call", new WriteToolArguments("source.txt", "changed"),
                ToolUpdateSink.noop(), cancellation).toCompletableFuture().join();
        var edit = new EditTool(directory).execute(
                "call", new EditToolArguments("source.txt", List.of(
                        new EditReplacement("original", "changed"))),
                ToolUpdateSink.noop(), cancellation).toCompletableFuture().join();

        assertTrue(write.error());
        assertTrue(edit.error());
        assertEquals("original", Files.readString(target));
    }

    @Test
    void argumentRecordsRejectInvalidDirectConstruction() {
        assertThrows(NullPointerException.class, () -> new WriteToolArguments(null, "x"));
        assertThrows(NullPointerException.class, () -> new WriteToolArguments("x", null));
        assertThrows(IllegalArgumentException.class,
                () -> new WriteToolArguments("bad\0path", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new EditReplacement("", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new EditToolArguments("x", List.of()));
        var mutable = new java.util.ArrayList<>(List.of(new EditReplacement("a", "b")));
        var arguments = new EditToolArguments("x", mutable);
        mutable.clear();
        assertEquals(1, arguments.edits().size());
        assertThrows(UnsupportedOperationException.class,
                () -> arguments.edits().add(new EditReplacement("c", "d")));
    }

    private static ToolExecutionResult execute(WriteTool tool, WriteToolArguments arguments) {
        return tool.execute("call", arguments, ToolUpdateSink.noop(), new MutableCancellationSignal())
                .toCompletableFuture().join();
    }

    private static ToolExecutionResult execute(EditTool tool, EditToolArguments arguments) {
        return tool.execute("call", arguments, ToolUpdateSink.noop(), new MutableCancellationSignal())
                .toCompletableFuture().join();
    }

    private static String text(ToolExecutionResult result) {
        return assertInstanceOf(Content.Text.class, result.content().getFirst()).text();
    }

    private static void assertNoMutationTempFiles(Path parent) throws Exception {
        try (var entries = Files.list(parent)) {
            assertTrue(entries.noneMatch(path -> path.getFileName().toString().startsWith(".jcode-")));
        }
    }
}
