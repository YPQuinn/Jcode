package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.session.SessionEntry;
import site.pplee.jcode.codingagent.session.SessionFileLockException;
import site.pplee.jcode.codingagent.session.SessionFormatException;
import site.pplee.jcode.codingagent.session.SessionHeader;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionFileTest {
    private static final Instant T1 = Instant.parse("2026-09-20T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void createAppendCloseAndOpenPreserveJsonlOrder() throws Exception {
        var header = header();
        Path path;
        byte[] headerOnly;
        try (var file = SessionFile.create(tempDir.resolve("sessions"), header)) {
            path = file.path();
            assertEquals(header, file.header());
            assertTrue(Files.isRegularFile(path));
            headerOnly = Files.readAllBytes(path);

            file.append(message("one", null, "第一行\n第二行"));
            file.append(message("two", "one", "second"));
            assertEquals(List.of("one", "two"), entryIds(path));
        }

        var complete = Files.readAllBytes(path);
        assertArrayEquals(headerOnly, java.util.Arrays.copyOf(complete, headerOnly.length));
        assertEquals(3, Files.readAllLines(path, StandardCharsets.UTF_8).size());
        assertEquals((byte) '\n', complete[complete.length - 1]);

        try (var reopened = SessionFile.open(path)) {
            assertEquals(header, reopened.header());
            assertEquals(List.of("one", "two"), entryIds(path));
            assertNull(reopened.recovery());
        }
    }

    @Test
    void completeFinalRecordWithoutLineFeedIsAcceptedAndNextAppendAddsSeparator() throws Exception {
        Path path;
        try (var file = SessionFile.create(tempDir, header())) {
            path = file.path();
            file.append(message("one", null, "one"));
        }
        truncateLastByte(path);

        try (var reopened = SessionFile.open(path)) {
            assertNull(reopened.recovery());
            reopened.append(message("two", "one", "two"));
        }

        var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).contains("\"id\":\"one\""));
        assertTrue(lines.get(2).contains("\"id\":\"two\""));
    }

    @Test
    void truncatedJsonTailIsReportedPreservedUntilAppendAndThenReplaced() throws Exception {
        Path path;
        try (var file = SessionFile.create(tempDir, header())) {
            path = file.path();
            file.append(message("one", null, "one"));
        }
        Files.writeString(path, "{\"type\":\"message\"", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        var beforeOpen = Files.readAllBytes(path);

        try (var reopened = SessionFile.open(path)) {
            var recovery = reopened.recovery();
            assertNotNull(recovery);
            assertEquals(SessionFileReader.TailReason.TRUNCATED_JSON, recovery.reason());
            assertArrayEquals(beforeOpen, Files.readAllBytes(path), "open must not repair the file");
            assertEquals(List.of("one"), entryIds(path));

            reopened.append(message("two", "one", "two"));
            assertNull(reopened.recovery());
        }

        try (var verified = SessionFile.open(path)) {
            assertEquals(List.of("one", "two"), entryIds(path));
        }
        assertFalse(Files.readString(path).contains("{\"type\":\"message\"{\"type\""));
    }

    @Test
    void incompleteUtf8TailIsRecoverableButInvalidUtf8IsNot() throws Exception {
        Path recoverablePath;
        try (var file = SessionFile.create(tempDir.resolve("recoverable"), header())) {
            recoverablePath = file.path();
        }
        Files.write(recoverablePath, new byte[]{(byte) 0xE4, (byte) 0xB8}, StandardOpenOption.APPEND);
        try (var reopened = SessionFile.open(recoverablePath)) {
            assertNotNull(reopened.recovery());
            assertEquals(SessionFileReader.TailReason.INCOMPLETE_UTF8,
                    reopened.recovery().reason());
            reopened.append(message("one", null, "你好"));
        }

        Path invalidPath;
        try (var file = SessionFile.create(tempDir.resolve("invalid"), headerWithId(2))) {
            invalidPath = file.path();
        }
        Files.write(invalidPath, new byte[]{(byte) 0xFF}, StandardOpenOption.APPEND);
        var original = Files.readAllBytes(invalidPath);
        var failure = assertThrows(SessionFormatException.class, () -> SessionFile.open(invalidPath));
        assertEquals(2, failure.lineNumber());
        assertArrayEquals(original, Files.readAllBytes(invalidPath));

        Path invalidPrefixPath;
        try (var file = SessionFile.create(tempDir.resolve("invalid-prefix"), headerWithId(3))) {
            invalidPrefixPath = file.path();
        }
        Files.write(invalidPrefixPath,
                new byte[]{(byte) 0xFF, (byte) 0xE4, (byte) 0xB8},
                StandardOpenOption.APPEND);
        assertThrows(SessionFormatException.class, () -> SessionFile.open(invalidPrefixPath));

        Path invalidCodePointPath;
        try (var file = SessionFile.create(tempDir.resolve("invalid-code-point"), headerWithId(4))) {
            invalidCodePointPath = file.path();
        }
        Files.write(invalidCodePointPath,
                new byte[]{(byte) 0xE0, (byte) 0x80},
                StandardOpenOption.APPEND);
        assertThrows(SessionFormatException.class, () -> SessionFile.open(invalidCodePointPath));
    }

    @Test
    void malformedMiddleOrCompleteFinalRecordIsHardFailureAndDoesNotModifyFile() throws Exception {
        var codec = new SessionCodec();
        var headerLine = new String(codec.encodeHeader(header()), StandardCharsets.UTF_8);
        var validEntry = new String(codec.encodeEntry(message("one", null, "one")), StandardCharsets.UTF_8);
        var middlePath = tempDir.resolve("middle.jsonl");
        Files.writeString(middlePath, headerLine + "\n{broken}\n" + validEntry + "\n");
        var middleBytes = Files.readAllBytes(middlePath);

        var middleFailure = assertThrows(
                SessionFormatException.class, () -> SessionFile.open(middlePath));
        assertEquals(2, middleFailure.lineNumber());
        assertArrayEquals(middleBytes, Files.readAllBytes(middlePath));

        var finalPath = tempDir.resolve("final.jsonl");
        Files.writeString(finalPath, headerLine + "\nnot-json");
        var finalBytes = Files.readAllBytes(finalPath);
        assertThrows(SessionFormatException.class, () -> SessionFile.open(finalPath));
        assertArrayEquals(finalBytes, Files.readAllBytes(finalPath));

        var trailingValuePath = tempDir.resolve("trailing-value.jsonl");
        Files.writeString(trailingValuePath, headerLine + "\n" + validEntry + " {");
        var trailingValueBytes = Files.readAllBytes(trailingValuePath);
        assertThrows(SessionFormatException.class, () -> SessionFile.open(trailingValuePath));
        assertArrayEquals(trailingValueBytes, Files.readAllBytes(trailingValuePath));
    }

    @Test
    void missingOrTruncatedHeaderIsNeverRecoveredAndFailedOpenReleasesLock() throws Exception {
        var empty = tempDir.resolve("empty.jsonl");
        Files.createFile(empty);
        var emptyFailure = assertThrows(SessionFormatException.class, () -> SessionFile.open(empty));
        assertEquals(1, emptyFailure.lineNumber());
        assertEquals(0, emptyFailure.byteOffset());

        var truncated = tempDir.resolve("truncated-header.jsonl");
        Files.writeString(truncated, "{\"type\":\"session\"");
        var original = Files.readAllBytes(truncated);
        var truncatedFailure = assertThrows(
                SessionFormatException.class, () -> SessionFile.open(truncated));
        assertEquals(1, truncatedFailure.lineNumber());
        assertArrayEquals(original, Files.readAllBytes(truncated));

        var codec = new SessionCodec();
        Files.write(truncated, concatLines(codec.encodeHeader(header())));
        try (var reopened = SessionFile.open(truncated)) {
            assertEquals(header(), reopened.header());
        }
    }

    @Test
    void unsupportedVersionAndBrokenParentRelationReportPhysicalLocation() throws Exception {
        var unsupported = tempDir.resolve("unsupported.jsonl");
        Files.writeString(unsupported, """
                {"type":"session","version":99,"id":"00000000-0000-0000-0000-000000000001","timestamp":"2026-09-20T00:00:00Z","cwd":"/tmp"}
                """);
        var versionFailure = assertThrows(
                SessionFormatException.class, () -> SessionFile.open(unsupported));
        assertEquals(1, versionFailure.lineNumber());
        assertEquals(0, versionFailure.byteOffset());

        var codec = new SessionCodec();
        var broken = tempDir.resolve("broken-parent.jsonl");
        Files.write(broken, concatLines(
                codec.encodeHeader(header()),
                codec.encodeEntry(message("child", "missing", "child"))));
        var parentFailure = assertThrows(
                SessionFormatException.class, () -> SessionFile.open(broken));
        assertEquals(2, parentFailure.lineNumber());
        assertTrue(parentFailure.getMessage().contains("parent entry must appear earlier"));
    }

    @Test
    void readsAndRejectedDuplicateOpenDoNotReleaseOwnerLockInAnotherJvm() throws Exception {
        Path path;
        var owner = SessionFile.create(tempDir, header());
        path = owner.path();
        try {
            assertFalse(externalProcessCanAcquire(path));

            assertEquals(1, SessionFiles.list(tempDir).sessions().size());
            assertFalse(externalProcessCanAcquire(path),
                    "a discovery read must reuse the active owner's channel");

            assertThrows(SessionFileLockException.class, () -> SessionFile.open(path));
            assertFalse(externalProcessCanAcquire(path),
                    "a rejected duplicate writer must not open and close another channel");
        } finally {
            owner.close();
        }

        assertTrue(externalProcessCanAcquire(path));
        try (var reopened = SessionFile.open(path)) {
            reopened.append(message("one", null, "one"));
        }
    }

    @Test
    void appendLoopsOverShortWrites() throws Exception {
        Path path;
        try (var created = SessionFile.create(tempDir, header())) {
            path = created.path();
        }

        try (var file = SessionFile.open(path, channel -> buffer -> shortWrite(channel, buffer, 3))) {
            file.append(message("one", null, "a message longer than three bytes"));
        }

        try (var verified = SessionFile.open(path)) {
            assertEquals(List.of("one"), entryIds(path));
        }
    }

    @Test
    void failedAppendPoisonsCurrentOwnerAndNeverAcceptsEntryInMemory() throws Exception {
        Path path;
        try (var created = SessionFile.create(tempDir, header())) {
            path = created.path();
        }

        try (var file = SessionFile.open(path, channel -> buffer -> {
            shortWrite(channel, buffer, 5);
            throw new IOException("injected write failure");
        })) {
            var first = assertThrows(IOException.class,
                    () -> file.append(message("one", null, "one")));
            assertTrue(first.getMessage().contains("injected"));
            assertTrue(file.writeFailed());
            assertTrue(SessionFileAccess.read(path).entries().isEmpty());
            var sizeAfterFailure = Files.size(path);

            var second = assertThrows(IOException.class,
                    () -> file.append(message("two", null, "two")));
            assertTrue(second.getMessage().contains("uncertain"));
            assertEquals(sizeAfterFailure, Files.size(path));
        }

        try (var recovered = SessionFile.open(path)) {
            assertNotNull(recovered.recovery());
            recovered.append(message("replacement", null, "replacement"));
        }
    }

    @Test
    void writerThatNeverMakesProgressFailsDeterministically() throws Exception {
        Path path;
        try (var created = SessionFile.create(tempDir, header())) {
            path = created.path();
        }
        try (var file = SessionFile.open(path, channel -> buffer -> 0)) {
            var failure = assertThrows(IOException.class,
                    () -> file.append(message("one", null, "one")));
            assertTrue(failure.getMessage().contains("no progress"));
            assertTrue(file.writeFailed());
        }
    }

    private static boolean externalProcessCanAcquire(Path path) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java");
        var testClasses = Path.of(SessionLockProbe.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        var process = new ProcessBuilder(
                java.toString(),
                "-cp",
                testClasses.toString(),
                SessionLockProbe.class.getName(),
                path.toString())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS),
                    "session lock probe did not terminate after forced destruction");
            throw new AssertionError("session lock probe timed out");
        }
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.exitValue() == 0 || process.exitValue() == 2,
                () -> "unexpected session lock probe exit " + process.exitValue() + ": " + output);
        return process.exitValue() == 0;
    }

    private static List<String> entryIds(Path path) throws IOException {
        return SessionFileAccess.read(path).entries().stream().map(SessionEntry::id).toList();
    }

    private SessionHeader header() {
        return headerWithId(1);
    }

    private SessionHeader headerWithId(long suffix) {
        return new SessionHeader(
                UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix)),
                T1,
                tempDir);
    }

    private static SessionMessageEntry message(String id, String parentId, String text) {
        return new SessionMessageEntry(
                id,
                parentId,
                T1,
                StandardAgentMessage.of(new Message.User(List.of(new Content.Text(text)), T1)));
    }

    private static void truncateLastByte(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() - 1);
        }
    }

    private static int shortWrite(FileChannel channel, ByteBuffer source, int maximum)
            throws IOException {
        int originalLimit = source.limit();
        source.limit(Math.min(originalLimit, source.position() + maximum));
        try {
            return channel.write(source);
        } finally {
            source.limit(originalLimit);
        }
    }

    private static byte[] concatLines(byte[]... records) {
        int length = java.util.Arrays.stream(records).mapToInt(record -> record.length + 1).sum();
        var result = ByteBuffer.allocate(length);
        for (var record : records) {
            result.put(record).put((byte) '\n');
        }
        return result.array();
    }
}
