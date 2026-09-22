package site.pplee.jcode.codingagent.settings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Narrow short-lived lock and atomic JSON replacement helper for settings/trust files. */
final class ConfigFileUpdater {
    private static final Object RESERVATION_LOCK = new Object();
    private static final Set<Path> RESERVED = new HashSet<>();

    private ConfigFileUpdater() {
    }

    static void update(
            Path target,
            ObjectMapper mapper,
            boolean privateFile,
            JsonMutation mutation
    ) throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        Objects.requireNonNull(mutation, "mutation must not be null");
        Path normalized = target.toAbsolutePath().normalize();
        createParent(normalized.getParent(), privateFile);
        Path identity = identity(normalized);
        reserve(identity);
        try {
            updateReserved(identity, mapper, privateFile, mutation);
        } finally {
            synchronized (RESERVATION_LOCK) {
                RESERVED.remove(identity);
            }
        }
    }

    private static void updateReserved(
            Path target,
            ObjectMapper mapper,
            boolean privateFile,
            JsonMutation mutation
    ) throws IOException {
        Path lockPath = target.resolveSibling(target.getFileName() + ".lock");
        boolean committed = false;
        try (var channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            if (privateFile) {
                setPrivatePermissions(lockPath);
            }
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException failure) {
                throw new SettingsFileLockException(target, failure);
            }
            if (lock == null) {
                throw new SettingsFileLockException(target, null);
            }
            try (lock) {
                ObjectNode root = readRoot(target, mapper);
                mutation.apply(root);
                writeAtomically(target, root, mapper, privateFile);
                committed = true;
            }
        } catch (SettingsFileLockException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new SettingsWriteException(target, committed, failure);
        }
    }

    private static ObjectNode readRoot(Path target, ObjectMapper mapper) throws IOException {
        if (!Files.exists(target)) {
            return mapper.createObjectNode();
        }
        try (var input = Files.newInputStream(target)) {
            var root = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(input);
            if (!(root instanceof ObjectNode object)) {
                throw new IOException("configuration root must be an object");
            }
            return object;
        }
    }

    private static void writeAtomically(
            Path target,
            ObjectNode root,
            ObjectMapper mapper,
            boolean privateFile
    ) throws IOException {
        Path temporary = Files.createTempFile(
                target.getParent(), "." + target.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            if (privateFile) {
                setPrivatePermissions(temporary);
            } else if (Files.exists(target)) {
                copyPosixPermissions(target, temporary);
            }
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            try (var output = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    if (output.write(buffer) == 0) {
                        throw new IOException("configuration writer made no progress");
                    }
                }
                output.force(true);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("atomic configuration replacement is not supported", failure);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void createParent(Path parent, boolean privateDirectory) throws IOException {
        if (Files.exists(parent)) {
            return;
        }
        Files.createDirectories(parent);
        if (privateDirectory) {
            setPrivateDirectoryPermissions(parent);
        }
    }

    private static Path identity(Path target) throws IOException {
        if (Files.exists(target)) {
            return target.toRealPath();
        }
        return target.getParent().toRealPath().resolve(target.getFileName());
    }

    private static void reserve(Path identity) throws SettingsFileLockException {
        synchronized (RESERVATION_LOCK) {
            if (!RESERVED.add(identity)) {
                throw new SettingsFileLockException(identity, null);
            }
        }
    }

    private static void setPrivateDirectoryPermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // The host is responsible for protecting explicit config locations on non-POSIX systems.
        }
    }

    private static void setPrivatePermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // The host is responsible for protecting explicit config locations on non-POSIX systems.
        }
    }

    private static void copyPosixPermissions(Path source, Path target) throws IOException {
        try {
            Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source));
        } catch (UnsupportedOperationException ignored) {
            // No portable permission copy is available.
        }
    }

    @FunctionalInterface
    interface JsonMutation {
        void apply(ObjectNode root) throws IOException;
    }
}
