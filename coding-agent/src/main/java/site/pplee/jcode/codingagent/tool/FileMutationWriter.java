package site.pplee.jcode.codingagent.tool;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Performs bounded same-directory temporary writes followed by one atomic replacement. */
final class FileMutationWriter {
    static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final int BUFFER_BYTES = 16 * 1024;

    private final Path workingDirectory;
    private final FileMutationOperations operations;

    FileMutationWriter(Path workingDirectory) {
        this(workingDirectory, NioFileMutationOperations.INSTANCE);
    }

    FileMutationWriter(Path workingDirectory, FileMutationOperations operations) {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
    }

    /** Observe an existing regular file and retain the bytes used for edit planning. */
    FileSnapshot readExisting(String input, CancellationSignal cancellation) throws IOException {
        return observe(input, false, cancellation);
    }

    /** Create or replace a regular file after observing its pre-commit state. */
    void write(String input, byte[] finalBytes, CancellationSignal cancellation) throws IOException {
        commit(observe(input, true, cancellation), finalBytes, cancellation);
    }

    /** Commit bytes only if the file still matches the supplied observation. */
    void commit(FileSnapshot snapshot, byte[] finalBytes, CancellationSignal cancellation)
            throws IOException {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(finalBytes, "finalBytes must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        if (finalBytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("final file exceeds the 8 MiB limit");
        }

        cancellation.throwIfCancelled();
        Path temporary = operations.createTempFile(snapshot.realParent());
        boolean committed = false;
        Throwable primaryFailure = null;
        try {
            writeTemporary(temporary, finalBytes, cancellation);
            if (snapshot.permissions().isPresent()) {
                operations.setPosixPermissions(temporary, snapshot.permissions().orElseThrow());
            }
            verifyUnchanged(snapshot, cancellation);
            cancellation.throwIfCancelled();
            try {
                operations.moveAtomicReplace(temporary, snapshot.target());
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic replacement is not supported", e);
            }
            committed = true;
        } catch (IOException | RuntimeException e) {
            primaryFailure = e;
            throw e;
        } finally {
            if (!committed) {
                try {
                    operations.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    private FileSnapshot observe(
            String input,
            boolean createParentDirectories,
            CancellationSignal cancellation
    ) throws IOException {
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        FileToolSupport.validatePath(input);
        cancellation.throwIfCancelled();
        Path requested = resolve(input);
        var noFollow = operations.readAttributesNoFollow(requested);
        if (noFollow.isPresent()) {
            return observeExisting(requested, cancellation);
        }
        if (!createParentDirectories) {
            throw new NoSuchFileException("target file does not exist");
        }

        Path parent = requested.getParent();
        if (parent == null || requested.getFileName() == null) {
            throw new IOException("target must have a parent directory and file name");
        }
        Path realParent;
        try {
            realParent = operations.toRealPath(parent);
        } catch (NoSuchFileException e) {
            operations.createDirectories(parent);
            cancellation.throwIfCancelled();
            realParent = operations.toRealPath(parent);
        }
        if (!operations.readAttributes(realParent).isDirectory()) {
            throw new IOException("target parent is not a directory");
        }

        cancellation.throwIfCancelled();
        if (operations.readAttributesNoFollow(requested).isPresent()) {
            return observeExisting(requested, cancellation);
        }
        Path target = realParent.resolve(requested.getFileName());
        if (operations.readAttributesNoFollow(target).isPresent()) {
            throw new IOException("target appeared while preparing mutation");
        }
        return new FileSnapshot(
                requested, target, realParent, false, new byte[0], null,
                Optional.empty());
    }

    private FileSnapshot observeExisting(Path requested, CancellationSignal cancellation)
            throws IOException {
        Path target;
        try {
            target = operations.toRealPath(requested);
        } catch (NoSuchFileException e) {
            throw new IOException("target is a broken symbolic link or no longer exists", e);
        }
        BasicFileAttributes before = operations.readAttributes(target);
        if (!before.isRegularFile()) {
            throw new IOException("target is not a regular file");
        }
        if (before.size() > MAX_FILE_BYTES) {
            throw new IOException("existing file exceeds the 8 MiB limit");
        }
        byte[] bytes = readBounded(target, cancellation);
        BasicFileAttributes after = operations.readAttributes(target);
        Path observedTarget = operations.toRealPath(requested);
        if (!target.equals(observedTarget)
                || !sameIdentity(before, after)
                || after.size() != bytes.length) {
            throw new IOException("file changed while it was being read");
        }
        Optional<Set<PosixFilePermission>> permissions =
                operations.readPosixPermissions(target);
        cancellation.throwIfCancelled();
        return new FileSnapshot(
                requested, target, target.getParent(), true, bytes,
                after.fileKey(), permissions);
    }

    private void verifyUnchanged(FileSnapshot snapshot, CancellationSignal cancellation)
            throws IOException {
        cancellation.throwIfCancelled();
        if (!snapshot.existed()) {
            Path currentParent = operations.toRealPath(snapshot.requested().getParent());
            if (!snapshot.realParent().equals(currentParent)
                    || operations.readAttributesNoFollow(snapshot.requested()).isPresent()
                    || operations.readAttributesNoFollow(snapshot.target()).isPresent()) {
                throw new IOException("target changed before commit");
            }
            return;
        }

        final Path currentTarget;
        try {
            currentTarget = operations.toRealPath(snapshot.requested());
        } catch (IOException e) {
            throw new IOException("target changed before commit", e);
        }
        if (!snapshot.target().equals(currentTarget)) {
            throw new IOException("target changed before commit");
        }
        BasicFileAttributes attributes = operations.readAttributes(currentTarget);
        if (!attributes.isRegularFile()
                || !sameFileKey(snapshot.fileKey(), attributes.fileKey())
                || attributes.size() > MAX_FILE_BYTES) {
            throw new IOException("target changed before commit");
        }
        byte[] currentBytes = readBounded(currentTarget, cancellation);
        if (!Arrays.equals(snapshot.originalBytes(), currentBytes)) {
            throw new IOException("target changed before commit");
        }
        if (!snapshot.permissions().equals(operations.readPosixPermissions(currentTarget))) {
            throw new IOException("target permissions changed before commit");
        }
    }

    private byte[] readBounded(Path path, CancellationSignal cancellation) throws IOException {
        var output = new ByteArrayOutputStream();
        var buffer = new byte[BUFFER_BYTES];
        try (var input = operations.openInput(path)) {
            while (true) {
                cancellation.throwIfCancelled();
                int maximumRead = Math.min(buffer.length, MAX_FILE_BYTES + 1 - output.size());
                if (maximumRead <= 0) {
                    throw new IOException("file exceeds the 8 MiB limit");
                }
                int read = input.read(buffer, 0, maximumRead);
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                if (output.size() > MAX_FILE_BYTES) {
                    throw new IOException("file exceeds the 8 MiB limit");
                }
            }
        }
        cancellation.throwIfCancelled();
        return output.toByteArray();
    }

    private void writeTemporary(
            Path temporary,
            byte[] bytes,
            CancellationSignal cancellation
    ) throws IOException {
        try (var output = operations.openOutput(temporary)) {
            int offset = 0;
            while (offset < bytes.length) {
                cancellation.throwIfCancelled();
                int length = Math.min(BUFFER_BYTES, bytes.length - offset);
                output.write(bytes, offset, length);
                offset += length;
            }
            cancellation.throwIfCancelled();
        }
    }

    /** Preserve native traversal semantics, including {@code ..} after a symbolic link. */
    private Path resolve(String input) {
        Path requested = Path.of(input);
        return requested.isAbsolute() ? requested : workingDirectory.resolve(requested);
    }

    private static boolean sameIdentity(BasicFileAttributes before, BasicFileAttributes after) {
        return sameFileKey(before.fileKey(), after.fileKey())
                && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private static boolean sameFileKey(Object expected, Object actual) {
        return expected == null || actual == null
                ? expected == actual
                : expected.equals(actual);
    }

    /** Immutable observation used to detect external changes before commit. */
    record FileSnapshot(
            Path requested,
            Path target,
            Path realParent,
            boolean existed,
            byte[] originalBytes,
            Object fileKey,
            Optional<Set<PosixFilePermission>> permissions
    ) {
        FileSnapshot {
            requested = Objects.requireNonNull(requested, "requested must not be null");
            target = Objects.requireNonNull(target, "target must not be null");
            realParent = Objects.requireNonNull(realParent, "realParent must not be null");
            originalBytes = Objects.requireNonNull(
                    originalBytes, "originalBytes must not be null").clone();
            permissions = Objects.requireNonNull(
                    permissions, "permissions must not be null").map(Set::copyOf);
        }

        @Override
        public byte[] originalBytes() {
            return originalBytes.clone();
        }
    }
}
