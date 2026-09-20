package site.pplee.jcode.codingagent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;
import site.pplee.jcode.codingagent.support.NativeToolTestSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileAccessTest {
    @TempDir
    Path directory;

    @Test
    void directWriteDoesNotRequireReadingPreviousContents() throws Exception {
        NativeToolTestSupport.requirePosixProcessSupport();
        var target = Files.writeString(directory.resolve("target.txt"), "original");
        var writeOnly = PosixFilePermissions.fromString("-w-------");
        Files.setPosixFilePermissions(target, writeOnly);
        try {
            access().write("target.txt", bytes("replacement"), new MutableCancellationSignal());
            assertEquals(writeOnly, Files.getPosixFilePermissions(target));
        } finally {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        }
        assertEquals("replacement", Files.readString(target));
    }

    @Test
    void directWriteDoesNotAddAnOptimisticConflictProtocol() throws Exception {
        var target = Files.writeString(directory.resolve("target.txt"), "original");
        var files = access();
        assertArrayEquals(bytes("original"), files.readForEdit("target.txt", new MutableCancellationSignal()));
        Files.writeString(target, "external edit");
        files.write("target.txt", bytes("requested"), new MutableCancellationSignal());
        assertEquals("requested", Files.readString(target));
    }

    @Test
    void cancellationBeforeWritingPreservesOriginal() throws Exception {
        var target = Files.writeString(directory.resolve("target.txt"), "original");
        var cancellation = new MutableCancellationSignal();
        cancellation.cancel();
        assertThrows(CancellationException.class,
                () -> access().write("target.txt", bytes("requested"), cancellation));
        assertEquals("original", Files.readString(target));
    }

    @Test
    void filesystemFailureRetainsUsefulPathInformation() throws Exception {
        Files.writeString(directory.resolve("parent"), "not a directory");
        var failure = assertThrows(IOException.class,
                () -> access().write("parent/child", bytes("data"), new MutableCancellationSignal()));
        assertTrue(FileToolSupport.safeMessage(failure).contains("parent"));
    }

    @Test
    void editingStillBoundsItsInMemoryInput() throws Exception {
        Files.write(directory.resolve("large.txt"), new byte[LocalFileAccess.MAX_EDIT_BYTES + 1]);
        var failure = assertThrows(IOException.class,
                () -> access().readForEdit("large.txt", new MutableCancellationSignal()));
        assertTrue(failure.getMessage().contains("edit input exceeds"));
    }

    private LocalFileAccess access() {
        return new LocalFileAccess(directory);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
