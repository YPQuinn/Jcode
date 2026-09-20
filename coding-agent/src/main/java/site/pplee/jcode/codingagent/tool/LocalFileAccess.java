package site.pplee.jcode.codingagent.tool;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Native file access; writes retain file identity and do not provide rollback. */
final class LocalFileAccess {
    static final int MAX_EDIT_BYTES = 8 * 1024 * 1024;
    private final Path workingDirectory;

    LocalFileAccess(Path workingDirectory) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
    }

    /** Read the complete edit input within the editor's in-memory planning budget. */
    byte[] readForEdit(String input, CancellationSignal cancellation) throws IOException {
        Path path = resolve(input);
        cancellation.throwIfCancelled();
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) {
            throw new IOException("not a regular file: " + path);
        }
        if (attributes.size() > MAX_EDIT_BYTES) {
            throw new IOException("edit input exceeds the 8 MiB limit: " + path);
        }
        try (var stream = Files.newInputStream(path); var output = new ByteArrayOutputStream()) {
            var buffer = new byte[8192];
            while (true) {
                cancellation.throwIfCancelled();
                int count = stream.read(buffer, 0, Math.min(buffer.length, MAX_EDIT_BYTES + 1 - output.size()));
                if (count < 0) {
                    return output.toByteArray();
                }
                if (output.size() + count > MAX_EDIT_BYTES) {
                    throw new IOException("edit input exceeds the 8 MiB limit: " + path);
                }
                output.write(buffer, 0, count);
            }
        }
    }

    /** Create parents and write directly, following native symlink and hardlink semantics. */
    void write(String input, byte[] bytes, CancellationSignal cancellation) throws IOException {
        Path path = resolve(input);
        cancellation.throwIfCancelled();
        try {
            if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
                throw new IOException("not a regular file: " + path);
            }
        } catch (NoSuchFileException missing) {
            // The native write below creates missing files, including dangling link targets.
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        cancellation.throwIfCancelled();
        Files.write(path, bytes);
    }

    private Path resolve(String input) {
        Path path = Path.of(FileToolSupport.validatePath(input));
        return path.isAbsolute() ? path : workingDirectory.resolve(path);
    }
}
