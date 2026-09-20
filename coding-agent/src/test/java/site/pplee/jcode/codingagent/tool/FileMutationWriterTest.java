package site.pplee.jcode.codingagent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.agentcore.tool.ToolUpdateSink;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FileMutationWriterTest {
    @TempDir
    Path directory;

    @Test
    void externalChangeBeforeCommitWinsAndTemporaryFileIsRemoved() throws Exception {
        Path target = directory.resolve("target.txt");
        Files.writeString(target, "original");
        var operations = new ForwardingFileMutationOperations() {
            @Override
            public Path createTempFile(Path parent) throws IOException {
                Path temporary = super.createTempFile(parent);
                Files.writeString(target, "external");
                return temporary;
            }
        };

        var result = execute(new WriteTool(directory, operations),
                new WriteToolArguments("target.txt", "requested"),
                new MutableCancellationSignal());

        assertTrue(result.error());
        assertEquals("external", Files.readString(target));
        assertNoTemporaryFiles();
    }

    @Test
    void filesystemFailureDiagnosticDoesNotExposePathOrWorkingDirectory() throws Exception {
        Path target = directory.resolve("secret.txt");
        Files.writeString(target, "original");
        var operations = new ForwardingFileMutationOperations() {
            @Override
            public Path createTempFile(Path parent) throws IOException {
                throw new IOException(target.toString());
            }
        };

        var result = execute(new WriteTool(directory, operations),
                new WriteToolArguments("secret.txt", "requested"),
                new MutableCancellationSignal());

        assertTrue(result.error());
        assertEquals("write failed: filesystem I/O failure", text(result));
        assertFalse(text(result).contains(directory.toString()));
        assertFalse(text(result).contains("secret.txt"));
        assertEquals("original", Files.readString(target));
    }

    @Test
    void unsupportedAtomicMovePreservesOriginalAndRemovesTemporaryFile() throws Exception {
        Path target = directory.resolve("target.txt");
        Files.writeString(target, "original");
        var operations = new ForwardingFileMutationOperations() {
            @Override
            public void moveAtomicReplace(Path source, Path destination)
                    throws AtomicMoveNotSupportedException {
                throw new AtomicMoveNotSupportedException(
                        source.toString(), destination.toString(), "not supported");
            }
        };

        var result = execute(new WriteTool(directory, operations),
                new WriteToolArguments("target.txt", "requested"),
                new MutableCancellationSignal());

        assertTrue(result.error());
        assertTrue(text(result).contains("atomic replacement is not supported"));
        assertEquals("original", Files.readString(target));
        assertNoTemporaryFiles();
    }

    @Test
    void cancellationDuringTemporaryWritePreservesOriginalAndCleansUp() throws Exception {
        Path target = directory.resolve("target.txt");
        Files.writeString(target, "original");
        var cancellation = new MutableCancellationSignal();
        var cancelled = new AtomicBoolean();
        var operations = new ForwardingFileMutationOperations() {
            @Override
            public OutputStream openOutput(Path path) throws IOException {
                return new FilterOutputStream(super.openOutput(path)) {
                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        out.write(bytes, offset, length);
                        if (cancelled.compareAndSet(false, true)) {
                            cancellation.cancel();
                        }
                    }
                };
            }
        };

        var result = execute(new WriteTool(directory, operations),
                new WriteToolArguments("target.txt", "x".repeat(64 * 1024)),
                cancellation);

        assertTrue(result.error());
        assertTrue(text(result).contains("cancelled"));
        assertEquals("original", Files.readString(target));
        assertNoTemporaryFiles();
    }

    @Test
    void cancellationObservedAfterAtomicMoveReportsCommittedSuccess() throws Exception {
        Path target = directory.resolve("target.txt");
        Files.writeString(target, "original");
        var cancellation = new MutableCancellationSignal();
        var operations = new ForwardingFileMutationOperations() {
            @Override
            public void moveAtomicReplace(Path source, Path destination) throws IOException {
                super.moveAtomicReplace(source, destination);
                cancellation.cancel();
            }
        };

        var result = execute(new WriteTool(directory, operations),
                new WriteToolArguments("target.txt", "committed"), cancellation);

        assertFalse(result.error());
        assertEquals("committed", Files.readString(target));
    }

    @Test
    void temporaryWriteFailurePreservesOriginalAndCleansUp() throws Exception {
        Path target = directory.resolve("target.txt");
        Files.writeString(target, "original");
        var operations = new ForwardingFileMutationOperations() {
            @Override
            public OutputStream openOutput(Path path) throws IOException {
                return new FilterOutputStream(super.openOutput(path)) {
                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        out.write(bytes, offset, Math.min(5, length));
                        throw new IOException("injected write failure");
                    }
                };
            }
        };

        var result = execute(new WriteTool(directory, operations),
                new WriteToolArguments("target.txt", "requested"),
                new MutableCancellationSignal());

        assertTrue(result.error());
        assertEquals("original", Files.readString(target));
        assertNoTemporaryFiles();
    }

    @Test
    void editDetectsConflictAgainstTheBytesUsedForPlanning() throws Exception {
        Path target = directory.resolve("target.txt");
        Files.writeString(target, "before\n");
        var operations = new ForwardingFileMutationOperations() {
            @Override
            public Path createTempFile(Path parent) throws IOException {
                Path temporary = super.createTempFile(parent);
                Files.writeString(target, "external\n");
                return temporary;
            }
        };
        var tool = new EditTool(directory, operations);

        var result = tool.execute(
                        "call",
                        new EditToolArguments("target.txt", List.of(
                                new EditReplacement("before", "after"))),
                        ToolUpdateSink.noop(),
                        new MutableCancellationSignal())
                .toCompletableFuture().join();

        assertTrue(result.error());
        assertTrue(text(result).contains("changed before commit"));
        assertEquals("external\n", Files.readString(target));
        assertNoTemporaryFiles();
    }

    @Test
    void noChangeEditDoesNotCreateTemporaryFile() throws Exception {
        Path target = directory.resolve("target.txt");
        Files.writeString(target, "same\n");
        var operations = new ForwardingFileMutationOperations() {
            @Override
            public Path createTempFile(Path parent) {
                fail("no-change edit must not create a temporary file");
                return null;
            }
        };
        var tool = new EditTool(directory, operations);

        var result = tool.execute(
                        "call",
                        new EditToolArguments("target.txt", List.of(
                                new EditReplacement("same", "same"))),
                        ToolUpdateSink.noop(),
                        new MutableCancellationSignal())
                .toCompletableFuture().join();

        assertFalse(result.error());
        assertEquals("same\n", Files.readString(target));
    }

    private static site.pplee.jcode.agentcore.tool.ToolExecutionResult execute(
            WriteTool tool,
            WriteToolArguments arguments,
            MutableCancellationSignal cancellation
    ) {
        return tool.execute("call", arguments, ToolUpdateSink.noop(), cancellation)
                .toCompletableFuture().join();
    }

    private static String text(site.pplee.jcode.agentcore.tool.ToolExecutionResult result) {
        return ((site.pplee.jcode.ai.message.Content.Text) result.content().getFirst()).text();
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".jcode-")));
        }
    }
}
