package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.session.LabelEntry;
import site.pplee.jcode.codingagent.session.ModelChangeEntry;
import site.pplee.jcode.codingagent.session.SessionEntries;
import site.pplee.jcode.codingagent.session.SessionEntry;
import site.pplee.jcode.codingagent.session.SessionHeader;
import site.pplee.jcode.codingagent.session.SessionInfoEntry;
import site.pplee.jcode.codingagent.session.SessionMessageEntry;
import site.pplee.jcode.codingagent.session.SessionSnapshot;
import site.pplee.jcode.codingagent.session.ThinkingLevelChangeEntry;

import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Mutable owner of one in-memory or file-backed session tree. Product code
 * only exposes immutable {@link SessionSnapshot}s and serializes accepted mutations.
 */
final class SessionManager implements AutoCloseable {
    private static final int MAX_ID_ATTEMPTS = 100;

    private final SessionHeader header;
    private final Clock clock;
    private final Supplier<String> idGenerator;
    private final SessionFile sessionFile;
    private final List<SessionEntry> entries = new ArrayList<>();
    private final Map<String, SessionEntry> byId = new LinkedHashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private String currentEntryId;
    private String name;
    private ModelRef currentModel;
    private ThinkingLevel currentThinkingLevel;
    private boolean closed;

    SessionManager(SessionHeader header, Clock clock) {
        this(header, List.of(), clock, SessionManager::randomShortId, null);
    }

    SessionManager(
            SessionHeader header,
            List<? extends SessionEntry> entries,
            Clock clock,
            Supplier<String> idGenerator
    ) {
        this(header, entries, clock, idGenerator, null);
    }

    private SessionManager(
            SessionHeader header,
            List<? extends SessionEntry> entries,
            Clock clock,
            Supplier<String> idGenerator,
            SessionFile sessionFile
    ) {
        this.header = Objects.requireNonNull(header, "header must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.sessionFile = sessionFile;
        Objects.requireNonNull(entries, "entries must not be null");
        entries.forEach(this::acceptExisting);
        refreshCurrentConfiguration();
    }

    static SessionManager createFileBacked(
            SessionHeader header,
            Path directory,
            Clock clock
    ) throws IOException {
        return createFileBacked(header, directory, clock, SessionManager::randomShortId);
    }

    static SessionManager createFileBacked(
            SessionHeader header,
            Path directory,
            Clock clock,
            Supplier<String> idGenerator
    ) throws IOException {
        var file = SessionFile.createManaged(directory, header);
        return ownedFileManager(file, header, List.of(), clock, idGenerator);
    }

    static SessionManager openFileBacked(Path path, Clock clock) throws IOException {
        return openFileBacked(path, clock, SessionManager::randomShortId);
    }

    static SessionManager openFileBacked(
            Path path,
            Clock clock,
            Supplier<String> idGenerator
    ) throws IOException {
        var opened = SessionFile.openLoadedManaged(path);
        return ownedFileManager(
                opened.file(), opened.loaded().header(), opened.loaded().entries(), clock, idGenerator);
    }

    static SessionManager openFileBacked(
            Path path,
            Clock clock,
            Supplier<String> idGenerator,
            SessionFile.SessionByteWriterFactory writerFactory
    ) throws IOException {
        var opened = SessionFile.openLoadedManaged(path, writerFactory);
        return ownedFileManager(
                opened.file(), opened.loaded().header(), opened.loaded().entries(), clock, idGenerator);
    }

    synchronized SessionMessageEntry appendMessage(StandardAgentMessage message) throws IOException {
        return append(id -> new SessionMessageEntry(
                id, currentEntryId, clock.instant(), message));
    }

    synchronized SessionMessageEntry appendCompletedMessage(
            StandardAgentMessage message,
            ModelRef model,
            ThinkingLevel thinkingLevel
    ) throws IOException {
        if (!Objects.equals(currentModel, model)) {
            appendModelChange(model);
        }
        if (currentThinkingLevel != thinkingLevel) {
            appendThinkingLevelChange(thinkingLevel);
        }
        return appendMessage(message);
    }

    synchronized ModelChangeEntry appendModelChange(ModelRef model) throws IOException {
        return append(id -> new ModelChangeEntry(
                id, currentEntryId, clock.instant(), model));
    }

    synchronized ThinkingLevelChangeEntry appendThinkingLevelChange(ThinkingLevel thinkingLevel) throws IOException {
        return append(id -> new ThinkingLevelChangeEntry(
                id, currentEntryId, clock.instant(), thinkingLevel));
    }

    synchronized SessionInfoEntry setName(String name) throws IOException {
        return append(id -> new SessionInfoEntry(
                id, currentEntryId, clock.instant(), name));
    }

    synchronized LabelEntry setLabel(String targetId, String label) throws IOException {
        requireWritable();
        requireEntry(targetId);
        return append(id -> new LabelEntry(
                id, currentEntryId, clock.instant(), targetId, label));
    }

    synchronized void branch(String entryId) {
        requireOpen();
        currentEntryId = requireEntry(entryId).id();
        refreshCurrentConfiguration();
    }

    synchronized void resetLeaf() {
        requireOpen();
        currentEntryId = null;
        currentModel = null;
        currentThinkingLevel = null;
    }

    synchronized SessionSnapshot snapshot() {
        return new SessionSnapshot(header, entries, currentEntryId, name, labels);
    }

    Path filePath() {
        return sessionFile == null ? null : sessionFile.path();
    }

    SessionFileReader.TailRecovery recovery() {
        return sessionFile == null ? null : sessionFile.recovery();
    }

    synchronized SessionFileAccess.DiscoveryResult discoveryResult() {
        return new SessionFileAccess.DiscoveryResult(header, entries, recovery());
    }

    synchronized void requireWritable() throws IOException {
        requireOpen();
        if (sessionFile != null) {
            sessionFile.requireWritable();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (sessionFile != null) {
            sessionFile.close();
        }
    }

    private <T extends SessionEntry> T append(java.util.function.Function<String, T> factory)
            throws IOException {
        requireWritable();
        var entry = factory.apply(nextId());
        SessionEntries.validateNext(entry, byId);
        if (sessionFile != null) {
            sessionFile.append(entry);
        }
        acceptExisting(entry);
        return entry;
    }

    private void acceptExisting(SessionEntry source) {
        var entry = SessionEntries.copy(source);
        SessionEntries.validateNext(entry, byId);

        entries.add(entry);
        byId.put(entry.id(), entry);
        currentEntryId = entry.id();
        applyDerivedState(entry);
    }

    private void refreshCurrentConfiguration() {
        currentModel = null;
        currentThinkingLevel = null;
        var entry = currentEntryId == null ? null : byId.get(currentEntryId);
        while (entry != null && (currentModel == null || currentThinkingLevel == null)) {
            if (currentModel == null && entry instanceof ModelChangeEntry modelChange) {
                currentModel = modelChange.model();
            }
            if (currentThinkingLevel == null
                    && entry instanceof ThinkingLevelChangeEntry thinkingChange) {
                currentThinkingLevel = thinkingChange.thinkingLevel();
            }
            entry = entry.parentId() == null ? null : byId.get(entry.parentId());
        }
    }

    private void applyDerivedState(SessionEntry entry) {
        if (entry instanceof ModelChangeEntry modelChange) {
            currentModel = modelChange.model();
        } else if (entry instanceof ThinkingLevelChangeEntry thinkingChange) {
            currentThinkingLevel = thinkingChange.thinkingLevel();
        }
        if (entry instanceof SessionInfoEntry info) {
            name = info.name();
        } else if (entry instanceof LabelEntry label) {
            if (label.label() == null) {
                labels.remove(label.targetId());
            } else {
                labels.put(label.targetId(), label.label());
            }
        }
    }

    private SessionEntry requireEntry(String entryId) {
        Objects.requireNonNull(entryId, "entryId must not be null");
        var entry = byId.get(entryId);
        if (entry == null) {
            throw new IllegalArgumentException("unknown session entry id: " + entryId);
        }
        return entry;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("session manager is closed");
        }
    }

    private static SessionManager ownedFileManager(
            SessionFile file,
            SessionHeader header,
            List<? extends SessionEntry> entries,
            Clock clock,
            Supplier<String> idGenerator
    ) throws IOException {
        try {
            var manager = new SessionManager(header, entries, clock, idGenerator, file);
            file.attachDiscoverySource(manager::discoveryResult);
            return manager;
        } catch (RuntimeException | Error failure) {
            try {
                file.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private String nextId() {
        for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
            var candidate = Objects.requireNonNull(
                    idGenerator.get(), "idGenerator returned null");
            if (candidate.isBlank()) {
                throw new IllegalStateException("idGenerator returned a blank id");
            }
            if (!byId.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "could not generate a unique session entry id after " + MAX_ID_ATTEMPTS + " attempts");
    }

    private static String randomShortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
