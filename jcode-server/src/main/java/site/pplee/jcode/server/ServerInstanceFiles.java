package site.pplee.jcode.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/** Owns one data directory lock, private service token, and instance descriptor. */
final class ServerInstanceFiles implements AutoCloseable {
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final ObjectMapper mapper;
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final String instanceId = UUID.randomUUID().toString();
    private final String token;
    private boolean closed;

    private ServerInstanceFiles(ObjectMapper mapper, Path directory,
            FileChannel lockChannel, FileLock lock, String token) {
        this.mapper = mapper;
        this.directory = directory;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.token = token;
    }

    static ServerInstanceFiles acquire(Path requestedDirectory, ObjectMapper mapper)
            throws IOException {
        Files.createDirectories(requestedDirectory);
        Path directory = requestedDirectory.toRealPath();
        var channel = FileChannel.open(directory.resolve("server.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException failure) {
            channel.close();
            throw new IOException("another server owns this data directory", failure);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
        if (lock == null) {
            channel.close();
            throw new IOException("another server owns this data directory");
        }
        try {
            String token = readOrCreateToken(directory.resolve("service.token"));
            Files.deleteIfExists(directory.resolve("runtime.json"));
            return new ServerInstanceFiles(mapper, directory, channel, lock, token);
        } catch (IOException | RuntimeException failure) {
            try {
                lock.release();
            } catch (IOException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    String instanceId() {
        return instanceId;
    }

    String token() {
        return token;
    }

    Path tokenFile() {
        return directory.resolve("service.token");
    }

    Path runtimeFile() {
        return directory.resolve("runtime.json");
    }

    void publish(String endpoint) throws IOException {
        var descriptor = new RuntimeDescriptor(instanceId, endpoint,
                ProcessHandle.current().pid(), 1, 1);
        Path temporary = Files.createTempFile(directory, ".runtime-", ".json");
        try {
            mapper.writeValue(temporary.toFile(), descriptor);
            try {
                Files.move(temporary, runtimeFile(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, runtimeFile(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        try {
            Path runtime = runtimeFile();
            if (Files.isRegularFile(runtime, LinkOption.NOFOLLOW_LINKS)
                    && Files.size(runtime) <= 4_096
                    && instanceId.equals(mapper.readTree(runtime.toFile())
                            .path("instanceId").asText())) {
                Files.delete(runtime);
            }
        } catch (IOException | RuntimeException problem) {
            failure = problem instanceof IOException io
                    ? io : new IOException("runtime descriptor cleanup failed", problem);
        }
        try {
            lock.release();
        } catch (IOException problem) {
            if (failure == null) {
                failure = problem;
            } else {
                failure.addSuppressed(problem);
            }
        }
        try {
            lockChannel.close();
        } catch (IOException problem) {
            if (failure == null) {
                failure = problem;
            } else {
                failure.addSuppressed(problem);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String readOrCreateToken(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            if (Files.getFileStore(file.getParent()).supportsFileAttributeView("posix")) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } else {
                Files.createFile(file);
            }
            try {
                restrictToOwner(file);
                Files.writeString(file, generated + "\n", StandardCharsets.UTF_8);
            } catch (IOException failure) {
                Files.deleteIfExists(file);
                throw failure;
            }
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("service token must be a regular file");
        }
        restrictToOwner(file);
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(129);
        }
        if (bytes.length > 128) {
            throw new IOException("service token file is invalid");
        }
        String token = new String(bytes, StandardCharsets.UTF_8).strip();
        if (!token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IOException("service token file is invalid");
        }
        return token;
    }

    private static void restrictToOwner(Path file) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        }
    }

    record RuntimeDescriptor(
            String instanceId,
            String endpoint,
            long pid,
            int httpMajorVersion,
            int eventSchemaVersion
    ) { }
}
