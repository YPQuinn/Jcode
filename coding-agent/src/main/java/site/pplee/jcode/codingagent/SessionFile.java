package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.session.SessionEntry;
import site.pplee.jcode.codingagent.session.SessionFileLockException;
import site.pplee.jcode.codingagent.session.SessionHeader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/** Exclusive owner of one append-only session JSONL file. */
final class SessionFile implements AutoCloseable {
    private static final byte LINE_FEED = (byte) '\n';
    private static final int MAX_ZERO_PROGRESS_WRITES = 16;
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuuMMdd'T'HHmmss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final Path path;
    private final SessionCodec codec;
    private final FileChannel channel;
    private final FileLock lock;
    private final SessionByteWriter writer;
    private final SessionHeader header;
    private final SessionFileAccess.WriterRegistration registration;
    private long appendOffset;
    private boolean needsSeparator;
    private SessionFileReader.TailRecovery recovery;
    private boolean writeFailed;
    private boolean closing;
    private boolean closed;

    private SessionFile(
            Path path,
            SessionCodec codec,
            FileChannel channel,
            FileLock lock,
            SessionByteWriter writer,
            SessionFileReader.ReadResult loaded,
            SessionFileAccess.WriterRegistration registration
    ) {
        this.path = path;
        this.codec = codec;
        this.channel = channel;
        this.lock = lock;
        this.writer = writer;
        this.header = loaded.header();
        this.registration = registration;
        this.appendOffset = loaded.appendOffset();
        this.needsSeparator = loaded.needsSeparator();
        this.recovery = loaded.recovery();
    }

    static SessionFile create(Path directory, SessionHeader header) throws IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(header, "header must not be null");
        var normalizedDirectory = directory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedDirectory);
        var path = normalizedDirectory.resolve(fileName(header));
        var registration = SessionFileAccess.reserveWriter(path);
        var codec = new SessionCodec();
        FileChannel channel = null;
        FileLock lock = null;
        boolean created = false;
        try {
            channel = FileChannel.open(path,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            created = true;
            lock = acquireLock(path, channel);
            var headerBytes = codec.encodeHeader(header);
            writeFully(channel::write, recordBuffer(false, headerBytes));
            var loaded = new SessionFileReader.ReadResult(
                    header, List.of(), channel.position(), false, null);
            var file = new SessionFile(
                    path, codec, channel, lock, channel::write, loaded, registration);
            SessionFileAccess.activate(registration, file);
            return file;
        } catch (Throwable failure) {
            closeAfterFailure(lock, channel, failure);
            SessionFileAccess.releaseFailed(registration);
            if (created) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    static SessionFile open(Path path) throws IOException {
        return openLoaded(path, channel -> channel::write).file();
    }

    static SessionFile open(Path path, SessionByteWriterFactory writerFactory) throws IOException {
        return openLoaded(path, writerFactory).file();
    }

    static Opened openLoaded(Path path) throws IOException {
        return openLoaded(path, channel -> channel::write);
    }

    static Opened openLoaded(Path path, SessionByteWriterFactory writerFactory)
            throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(writerFactory, "writerFactory must not be null");
        var normalized = path.toAbsolutePath().normalize();
        var registration = SessionFileAccess.reserveWriter(normalized);
        var codec = new SessionCodec();
        FileChannel channel = null;
        FileLock lock = null;
        try {
            channel = FileChannel.open(normalized, StandardOpenOption.READ, StandardOpenOption.WRITE);
            lock = acquireLock(normalized, channel);
            var loaded = new SessionFileReader(normalized, codec).read(channel);
            channel.position(loaded.appendOffset());
            var writer = Objects.requireNonNull(
                    writerFactory.create(channel), "writerFactory returned null");
            var file = new SessionFile(
                    normalized, codec, channel, lock, writer, loaded, registration);
            SessionFileAccess.activate(registration, file);
            return new Opened(file, loaded);
        } catch (Throwable failure) {
            closeAfterFailure(lock, channel, failure);
            SessionFileAccess.releaseFailed(registration);
            throw failure;
        }
    }

    synchronized void append(SessionEntry entry) throws IOException {
        requireWritable();
        var json = codec.encodeEntry(Objects.requireNonNull(entry, "entry must not be null"));
        var buffer = recordBuffer(needsSeparator, json);
        try {
            if (recovery != null) {
                channel.truncate(appendOffset);
            }
            channel.position(appendOffset);
            writeFully(writer, buffer);
            appendOffset = channel.position();
        } catch (IOException | RuntimeException e) {
            writeFailed = true;
            throw e;
        }
        recovery = null;
        needsSeparator = false;
    }

    Path path() {
        return path;
    }

    SessionHeader header() {
        return header;
    }

    synchronized SessionFileReader.TailRecovery recovery() {
        return recovery;
    }

    synchronized boolean writeFailed() {
        return writeFailed;
    }

    synchronized void requireWritable() throws IOException {
        if (closed || closing) {
            throw new IOException("session file is closed: " + path);
        }
        if (writeFailed) {
            throw new IOException(
                    "session file append state is uncertain after a prior write failure: " + path);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            if (closed || closing) {
                return;
            }
            closing = true;
        }
        SessionFileAccess.close(registration);
    }

    synchronized SessionFileReader.ReadResult readForDiscovery() throws IOException {
        if (closed) {
            throw new IOException("session file is closed: " + path);
        }
        try {
            return new SessionFileReader(path, new SessionCodec()).read(channel);
        } finally {
            if (channel.isOpen()) {
                channel.position(appendOffset);
            }
        }
    }

    synchronized void closeOwnedResources() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException e) {
            failure = e;
        }
        try {
            channel.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static FileLock acquireLock(Path path, FileChannel channel) throws IOException {
        final FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (IOException | OverlappingFileLockException
                 | UnsupportedOperationException | SecurityException e) {
            throw new SessionFileLockException(path, e);
        }
        if (lock == null) {
            throw new SessionFileLockException(path, null);
        }
        return lock;
    }

    private static ByteBuffer recordBuffer(boolean prefixSeparator, byte[] json) {
        var buffer = ByteBuffer.allocate(json.length + (prefixSeparator ? 2 : 1));
        if (prefixSeparator) {
            buffer.put(LINE_FEED);
        }
        buffer.put(json);
        buffer.put(LINE_FEED);
        return buffer.flip();
    }

    static void writeFully(SessionByteWriter writer, ByteBuffer buffer) throws IOException {
        int zeroProgressWrites = 0;
        while (buffer.hasRemaining()) {
            int before = buffer.remaining();
            int written = writer.write(buffer);
            if (written < 0 || written > before) {
                throw new IOException("invalid session writer result: " + written);
            }
            if (written == 0) {
                zeroProgressWrites++;
                if (zeroProgressWrites >= MAX_ZERO_PROGRESS_WRITES) {
                    throw new IOException("session writer made no progress");
                }
            } else {
                zeroProgressWrites = 0;
            }
        }
    }

    private static String fileName(SessionHeader header) {
        return FILE_TIMESTAMP.format(header.timestamp()) + "-" + header.id() + ".jsonl";
    }

    private static void closeAfterFailure(FileLock lock, FileChannel channel, Throwable failure) {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    record Opened(SessionFile file, SessionFileReader.ReadResult loaded) {
    }

    @FunctionalInterface
    interface SessionByteWriter {
        int write(ByteBuffer buffer) throws IOException;
    }

    @FunctionalInterface
    interface SessionByteWriterFactory {
        SessionByteWriter create(FileChannel channel);
    }
}
