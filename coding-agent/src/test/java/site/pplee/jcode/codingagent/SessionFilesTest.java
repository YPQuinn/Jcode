package site.pplee.jcode.codingagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.codingagent.session.SessionHeader;
import site.pplee.jcode.codingagent.session.SessionInfoEntry;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionFilesTest {
    private static final Instant CREATED = Instant.parse("2026-09-20T12:00:00Z");

    @TempDir
    Path directory;

    @Test
    void missingDirectoryIsEmptyAndNonDirectoryFails() throws Exception {
        var missing = SessionFiles.list(directory.resolve("missing"));
        assertTrue(missing.sessions().isEmpty());
        assertTrue(missing.diagnostics().isEmpty());
        assertTrue(SessionFiles.latest(directory.resolve("missing")).isEmpty());

        var file = Files.writeString(directory.resolve("not-a-directory"), "fixture");
        assertThrows(NotDirectoryException.class, () -> SessionFiles.list(file));
    }

    @Test
    void listsValidFilesLatestFirstFiltersCwdAndIsolatesBadFiles() throws Exception {
        var cwdA = Files.createDirectory(directory.resolve("project-a"));
        var cwdB = Files.createDirectory(directory.resolve("project-b"));
        var sessions = Files.createDirectory(directory.resolve("sessions"));
        var older = createSession(
                sessions,
                "00000000-0000-0000-0000-000000000001",
                cwdA,
                CREATED,
                CREATED.plusSeconds(10),
                "older",
                true);
        var newer = createSession(
                sessions,
                "00000000-0000-0000-0000-000000000002",
                cwdB,
                CREATED.plusSeconds(1),
                CREATED.plusSeconds(20),
                null,
                false);
        Files.writeString(sessions.resolve("broken.jsonl"), "{not-json}\n");
        Files.writeString(sessions.resolve("ignored.txt"), "not a session");

        var result = SessionFiles.list(sessions);

        assertEquals(List.of(newer, older), result.sessions().stream()
                .map(info -> info.path())
                .toList());
        assertEquals(List.of(1L, 2L), result.sessions().stream()
                .map(info -> info.messageCount())
                .toList());
        assertEquals(CREATED.plusSeconds(20), result.sessions().getFirst().modified());
        assertEquals(CREATED.plusSeconds(10), result.sessions().getLast().modified(),
                "a later tool-result record must not change user/assistant activity time");
        assertEquals("older", result.sessions().getLast().name().orElseThrow());
        assertEquals(1, result.diagnostics().size());
        assertEquals(SessionFileDiagnostic.Kind.INVALID_SESSION,
                result.diagnostics().getFirst().kind());
        assertEquals(newer, SessionFiles.latest(sessions).orElseThrow().path());

        var filtered = SessionFiles.list(sessions, cwdA.resolve(".").toAbsolutePath());
        assertEquals(List.of(older), filtered.sessions().stream().map(info -> info.path()).toList());
        assertEquals(1, filtered.diagnostics().size(),
                "cwd filtering must not suppress independent file diagnostics");
        assertEquals(older, SessionFiles.latest(sessions, cwdA).orElseThrow().path());
    }

    @Test
    void equalActivityUsesStablePathOrderAndRecoverableTailIsReadOnly() throws Exception {
        var cwd = Files.createDirectory(directory.resolve("project"));
        var sessions = Files.createDirectory(directory.resolve("sessions"));
        var first = createSession(
                sessions,
                "00000000-0000-0000-0000-000000000010",
                cwd,
                CREATED,
                CREATED.plusSeconds(5),
                null,
                false);
        var second = createSession(
                sessions,
                "00000000-0000-0000-0000-000000000020",
                cwd,
                CREATED,
                CREATED.plusSeconds(5),
                null,
                false);
        Files.writeString(second, "{\"type\":\"message\"", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        var before = Files.readAllBytes(second);

        var result = SessionFiles.list(sessions);

        assertEquals(List.of(first, second).stream().sorted().toList(),
                result.sessions().stream().map(info -> info.path()).toList());
        assertEquals(1, result.diagnostics().size());
        assertEquals(SessionFileDiagnostic.Kind.RECOVERED_TAIL,
                result.diagnostics().getFirst().kind());
        assertArrayEquals(before, Files.readAllBytes(second),
                "listing must not repair or otherwise modify the file");
    }

    private static Path createSession(
            Path directory,
            String id,
            Path cwd,
            Instant created,
            Instant activity,
            String name,
            boolean appendToolResult
    ) throws Exception {
        var header = new SessionHeader(UUID.fromString(id), created, cwd);
        try (var file = SessionFile.create(directory, header)) {
            String parent = null;
            if (name != null) {
                var info = new SessionInfoEntry("name", parent, created.plusSeconds(1), name);
                file.append(info);
                parent = info.id();
            }
            var user = new SessionMessageEntry(
                    "user",
                    parent,
                    activity,
                    StandardAgentMessage.of(new Message.User(
                            List.of(new Content.Text("question")), activity)));
            file.append(user);
            parent = user.id();
            if (appendToolResult) {
                file.append(new SessionMessageEntry(
                        "tool-result",
                        parent,
                        activity.plusSeconds(50),
                        StandardAgentMessage.of(new Message.ToolResultMessage(
                                "call", "read", List.of(new Content.Text("result")),
                                false, activity.plusSeconds(50)))));
            }
            return file.path();
        }
    }
}
