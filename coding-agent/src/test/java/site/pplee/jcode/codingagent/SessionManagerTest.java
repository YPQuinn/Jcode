package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.session.LabelEntry;
import site.pplee.jcode.codingagent.session.ModelChangeEntry;
import site.pplee.jcode.codingagent.session.SessionContext;
import site.pplee.jcode.codingagent.session.SessionContextBuilder;
import site.pplee.jcode.codingagent.session.SessionEntry;
import site.pplee.jcode.codingagent.session.SessionHeader;
import site.pplee.jcode.codingagent.session.SessionInfoEntry;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;
import site.pplee.jcode.codingagent.session.SessionSnapshot;
import site.pplee.jcode.codingagent.session.ThinkingLevelChangeEntry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.message.Content;
import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.ai.message.StopReason;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {
    private static final Instant T1 = Instant.parse("2026-09-20T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-20T00:00:01Z");
    private static final Clock CLOCK = Clock.fixed(T1, ZoneOffset.UTC);
    private static final ModelRef MODEL = new ModelRef("test", "responses", "model-1");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void headerAndFiveEntryTypesExposeStableVersionOneShape() {
        var header = header();
        assertEquals("session", header.type());
        assertEquals(1, header.version());
        assertEquals(tempDir.toAbsolutePath().normalize(), header.cwd());

        var message = new SessionMessageEntry("m", null, T1, user("hello"));
        var model = new ModelChangeEntry("model", "m", T1, MODEL);
        var thinking = new ThinkingLevelChangeEntry("thinking", "model", T1, ThinkingLevel.HIGH);
        var info = new SessionInfoEntry("info", "thinking", T1, "name");
        var label = new LabelEntry("label", "info", T1, "m", "start");

        assertEquals(List.of(
                        "message", "model_change", "thinking_level_change", "session_info", "label"),
                List.of(message.type(), model.type(), thinking.type(), info.type(), label.type()));
    }

    @Test
    void branchesPreserveOldHistoryAndContextUsesOnlySelectedParentChain() throws Exception {
        var ids = ids("model", "thinking", "user", "original", "alternate", "root-two");
        var manager = new SessionManager(header(), List.of(), CLOCK, ids::remove);
        manager.appendModelChange(MODEL);
        manager.appendThinkingLevelChange(ThinkingLevel.HIGH);
        var user = manager.appendMessage(user("question"));
        var original = manager.appendMessage(assistant("original"));

        manager.branch(user.id());
        var alternate = manager.appendMessage(assistant("alternate"));
        manager.resetLeaf();
        var secondRoot = manager.appendMessage(user("new root"));
        var snapshot = manager.snapshot();

        assertEquals(List.of("question", "original"), texts(
                SessionContextBuilder.build(snapshot, original.id())));
        var alternateContext = SessionContextBuilder.build(snapshot, alternate.id());
        assertEquals(List.of("question", "alternate"), texts(alternateContext));
        assertEquals(MODEL, alternateContext.model().orElseThrow());
        assertEquals(ThinkingLevel.HIGH, alternateContext.thinkingLevel().orElseThrow());
        assertEquals(List.of("new root"), texts(SessionContextBuilder.build(snapshot)));
        assertEquals(secondRoot.id(), snapshot.currentEntryId().orElseThrow());
        assertEquals(6, snapshot.entries().size(), "moving the leaf must not append cursor entries");

        assertEquals(List.of(original.id(), alternate.id()), snapshot.children(user.id()).stream()
                .map(SessionEntry::id)
                .toList());
        assertEquals(2, snapshot.tree().size());
        assertEquals(original.id(), snapshot.entry(original.id()).orElseThrow().id());
    }

    @Test
    void namesAndLabelsUseLatestAppendGloballyButNeverEnterContext() throws Exception {
        var manager = new SessionManager(header(), List.of(), CLOCK,
                ids("message", "name-one", "label-one", "name-clear", "label-clear")::remove);
        var message = manager.appendMessage(user("hello"));
        manager.setName("demo");
        manager.setLabel(message.id(), "important");
        manager.branch(message.id());

        var labelled = manager.snapshot();
        assertEquals("demo", labelled.name().orElseThrow());
        assertEquals("important", labelled.label(message.id()).orElseThrow());
        assertEquals("important", labelled.tree().getFirst().label());
        assertEquals(List.of("hello"), texts(SessionContextBuilder.build(labelled)));
        assertEquals(3, labelled.entries().size(), "branch selection must not append an entry");

        manager.setName(null);
        manager.setLabel(message.id(), null);
        var cleared = manager.snapshot();
        assertTrue(cleared.name().isEmpty());
        assertTrue(cleared.label(message.id()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> manager.setLabel("missing", "label"));
    }

    @Test
    void generatedIdsRetryCollisionsAndLoadedEntriesRejectBrokenRelations() throws Exception {
        var generated = ids("same", "same", "next");
        var manager = new SessionManager(header(), List.of(), CLOCK, generated::remove);
        assertEquals("same", manager.appendMessage(user("one")).id());
        assertEquals("next", manager.appendMessage(user("two")).id());

        var root = new SessionMessageEntry("root", null, T1, user("root"));
        var duplicate = new SessionMessageEntry("root", null, T2, user("duplicate"));
        var forward = new SessionMessageEntry("child", "later", T1, user("child"));
        var missingLabel = new LabelEntry("label", null, T1, "missing", "value");

        assertThrows(IllegalArgumentException.class,
                () -> new SessionManager(header(), List.of(root, duplicate), CLOCK, () -> "unused"));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionManager(header(), List.of(forward), CLOCK, () -> "unused"));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionManager(header(), List.of(missingLabel), CLOCK, () -> "unused"));
    }

    @Test
    void treeDisplayOrderUsesTimestampThenAppendOrder() {
        var late = new SessionMessageEntry("late", null, T2, user("late"));
        var earlyOne = new SessionMessageEntry("early-one", null, T1, user("early one"));
        var earlyTwo = new SessionMessageEntry("early-two", null, T1, user("early two"));
        var manager = new SessionManager(
                header(), List.of(late, earlyOne, earlyTwo), CLOCK, () -> "unused");

        assertEquals(List.of("early-one", "early-two", "late"), manager.snapshot().tree().stream()
                .map(node -> node.entry().id())
                .toList());
    }

    @Test
    void messageJsonIsIsolatedAtAppendAndEverySnapshotExposure() throws Exception {
        var arguments = MAPPER.createObjectNode();
        arguments.put("path", "before");
        arguments.set("nested", MAPPER.valueToTree(Map.of("count", 1)));
        var toolCall = new Content.ToolCall("call-1", "read", arguments);
        var source = new Message.Assistant(
                List.of(toolCall), StopReason.TOOL_CALL, null,
                site.pplee.jcode.ai.message.Usage.zero(), T1);
        var manager = new SessionManager(header(), List.of(), CLOCK, ids("message")::remove);
        manager.appendMessage(StandardAgentMessage.of(source));

        arguments.put("path", "after");
        var snapshot = manager.snapshot();
        var firstRead = arguments(snapshot);
        assertEquals("before", firstRead.path("path").textValue());

        firstRead.put("path", "mutated-return");
        assertEquals("before", arguments(snapshot).path("path").textValue());
        assertFalse(snapshot.entries().isEmpty());
    }

    @Test
    void fileBackedManagerUsesSameTreeAndReopensAtLastPersistedEntry() throws Exception {
        Path path;
        try (var manager = SessionManager.createFileBacked(
                header(), tempDir.resolve("sessions"), CLOCK, ids("root", "child")::remove)) {
            var root = manager.appendMessage(user("root"));
            manager.appendMessage(assistant("child"));
            manager.branch(root.id());
            path = manager.filePath();
            assertTrue(Files.isRegularFile(path));
        }

        try (var reopened = SessionManager.openFileBacked(
                path, CLOCK, ids("next")::remove)) {
            var snapshot = reopened.snapshot();
            assertEquals("child", snapshot.currentEntryId().orElseThrow());
            assertEquals(List.of("root", "child"), texts(SessionContextBuilder.build(snapshot)));
            reopened.appendMessage(assistant("next"));
        }

        try (var verified = SessionManager.openFileBacked(
                path, CLOCK, ids("unused")::remove)) {
            assertEquals(List.of("root", "child", "next"), verified.snapshot().entries().stream()
                    .map(SessionEntry::id)
                    .toList());
        }
    }

    @Test
    void entryValidationRejectsBlankIdsSelfParentsAndUnknownQueries() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionInfoEntry(" ", null, T1, "name"));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionInfoEntry("same", "same", T1, "name"));

        var manager = new SessionManager(header(), List.of(), CLOCK, ids("root")::remove);
        manager.appendMessage(user("root"));
        var snapshot = manager.snapshot();
        assertThrows(IllegalArgumentException.class, () -> snapshot.branch("missing"));
        assertThrows(IllegalArgumentException.class, () -> snapshot.children("missing"));
        assertThrows(IllegalArgumentException.class, () -> snapshot.label("missing"));
    }

    private SessionHeader header() {
        return new SessionHeader(UUID.fromString("00000000-0000-0000-0000-000000000001"), T1, tempDir);
    }

    private static StandardAgentMessage user(String text) {
        return StandardAgentMessage.of(new Message.User(List.of(new Content.Text(text)), T1));
    }

    private static StandardAgentMessage assistant(String text) {
        return StandardAgentMessage.of(Message.Assistant.of(
                List.of(new Content.Text(text)), StopReason.STOP, T1));
    }

    private static ArrayDeque<String> ids(String... ids) {
        return new ArrayDeque<>(List.of(ids));
    }

    private static List<String> texts(SessionContext context) {
        return context.messages().stream()
                .map(StandardAgentMessage.class::cast)
                .map(StandardAgentMessage::message)
                .map(message -> switch (message) {
                    case Message.User user -> ((Content.Text) user.content().getFirst()).text();
                    case Message.Assistant assistant ->
                            ((Content.Text) assistant.content().getFirst()).text();
                    case Message.ToolResultMessage toolResult ->
                            ((Content.Text) toolResult.content().getFirst()).text();
                })
                .toList();
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode arguments(SessionSnapshot snapshot) {
        var entry = assertInstanceOf(SessionMessageEntry.class, snapshot.entries().getFirst());
        var standard = assertInstanceOf(StandardAgentMessage.class, entry.message());
        var assistant = assertInstanceOf(Message.Assistant.class, standard.message());
        var call = assertInstanceOf(Content.ToolCall.class, assistant.content().getFirst());
        return (com.fasterxml.jackson.databind.node.ObjectNode) call.arguments();
    }
}
