package site.pplee.jcode.codingagent;

import site.pplee.jcode.ai.message.Message;
import site.pplee.jcode.agentcore.message.AgentMessage;
import site.pplee.jcode.agentcore.message.StandardAgentMessage;
import site.pplee.jcode.agentcore.queue.PendingMessage;
import site.pplee.jcode.agentcore.queue.PendingMessageSource;
import site.pplee.jcode.agentcore.queue.QueueMode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Authoritative in-memory owner of supplemental inputs for one strict session.
 * The session lifecycle monitor serializes admission, claiming and history
 * confirmation without holding it across model, file or observer work.
 */
final class RunInputState {
    private final Object lock;
    private final Map<String, Entry> records = new LinkedHashMap<>();
    private final Set<String> usedRunIds = new HashSet<>();
    private final ArrayDeque<String> steering = new ArrayDeque<>();
    private final ArrayDeque<String> followUp = new ArrayDeque<>();
    private final PendingMessageSource steeringSource;
    private final PendingMessageSource followUpSource;
    private String activeRunId;
    private boolean accepting;

    RunInputState(Object lock, QueueMode steeringMode, QueueMode followUpMode) {
        this.lock = Objects.requireNonNull(lock, "lock must not be null");
        steeringSource = new Source(InputMode.STEER, steeringMode, steering);
        followUpSource = new Source(InputMode.FOLLOW_UP, followUpMode, followUp);
    }

    PendingMessageSource steeringSource() {
        return steeringSource;
    }

    PendingMessageSource followUpSource() {
        return followUpSource;
    }

    void start(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        synchronized (lock) {
            if (activeRunId != null) {
                throw new IllegalStateException("a run already owns supplemental inputs");
            }
            if (!usedRunIds.add(runId)) {
                throw new IllegalArgumentException("runId has already been used");
            }
            activeRunId = runId;
            accepting = true;
        }
    }

    InputRecord submit(InputRequest request, Message.User message) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(message, "message must not be null");
        synchronized (lock) {
            var existing = records.get(request.inputId());
            if (existing != null) {
                if (!existing.request.equals(request)) {
                    throw new IllegalArgumentException("inputId belongs to a different request");
                }
                return existing.snapshot();
            }
            if (!request.targetRunId().equals(activeRunId) || !accepting) {
                throw new IllegalStateException("target run is not accepting input");
            }
            var entry = new Entry(request, StandardAgentMessage.of(message));
            records.put(request.inputId(), entry);
            queue(request.mode()).addLast(request.inputId());
            return entry.snapshot();
        }
    }

    Optional<InputRecord> input(String inputId) {
        Objects.requireNonNull(inputId, "inputId must not be null");
        synchronized (lock) {
            var entry = records.get(inputId);
            return entry == null ? Optional.empty() : Optional.of(entry.snapshot());
        }
    }

    List<InputRecord> inputs(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        synchronized (lock) {
            return records.values().stream()
                    .filter(entry -> entry.request.targetRunId().equals(runId))
                    .map(Entry::snapshot)
                    .toList();
        }
    }

    boolean isActive(String runId) {
        synchronized (lock) {
            return Objects.equals(activeRunId, runId);
        }
    }

    void closeAdmission(String runId) {
        synchronized (lock) {
            if (Objects.equals(activeRunId, runId)) {
                accepting = false;
            }
        }
    }

    void closeAdmission() {
        synchronized (lock) {
            accepting = false;
        }
    }

    void finish(String reason) {
        synchronized (lock) {
            accepting = false;
            if (activeRunId == null) {
                return;
            }
            for (var entry : records.values()) {
                if (!entry.request.targetRunId().equals(activeRunId)) {
                    continue;
                }
                if (entry.phase == Phase.QUEUED || entry.phase == Phase.CLAIMED) {
                    entry.phase = Phase.NOT_APPLIED;
                    entry.reason = reason;
                } else if (entry.phase == Phase.APPLYING) {
                    entry.phase = Phase.RECONCILIATION_REQUIRED;
                    entry.reason = "history_outcome_unknown";
                }
            }
            steering.clear();
            followUp.clear();
            activeRunId = null;
        }
    }

    void applied(String inputId, String entryId) {
        synchronized (lock) {
            var entry = requireApplying(inputId);
            entry.entryId = Objects.requireNonNull(entryId, "entryId must not be null");
            entry.phase = Phase.APPLIED;
            entry.reason = null;
        }
    }

    private Entry requireApplying(String inputId) {
        var entry = records.get(Objects.requireNonNull(inputId, "inputId must not be null"));
        if (entry == null || entry.phase != Phase.APPLYING) {
            throw new IllegalStateException("input has no active history application");
        }
        return entry;
    }

    private ArrayDeque<String> queue(InputMode mode) {
        return mode == InputMode.STEER ? steering : followUp;
    }

    private enum Phase {
        QUEUED, CLAIMED, APPLYING, APPLIED, NOT_APPLIED, RECONCILIATION_REQUIRED
    }

    private static final class Entry {
        private final InputRequest request;
        private final AgentMessage message;
        private Phase phase = Phase.QUEUED;
        private String entryId;
        private String reason;

        private Entry(InputRequest request, AgentMessage message) {
            this.request = request;
            this.message = message;
        }

        private InputRecord snapshot() {
            var status = switch (phase) {
                case QUEUED, CLAIMED, APPLYING -> InputStatus.PENDING;
                case APPLIED -> InputStatus.APPLIED_TO_CONTEXT;
                case NOT_APPLIED -> InputStatus.NOT_APPLIED;
                case RECONCILIATION_REQUIRED -> InputStatus.RECONCILIATION_REQUIRED;
            };
            return new InputRecord(request, status, entryId, reason);
        }
    }

    private final class Source implements PendingMessageSource {
        private final InputMode mode;
        private final QueueMode queueMode;
        private final ArrayDeque<String> pending;

        private Source(InputMode mode, QueueMode queueMode, ArrayDeque<String> pending) {
            this.mode = mode;
            this.queueMode = Objects.requireNonNull(queueMode, "queueMode must not be null");
            this.pending = pending;
        }

        @Override
        public List<AgentMessage> drain() {
            throw new UnsupportedOperationException("strict inputs must retain their identities");
        }

        @Override
        public List<PendingMessage> drainPending() {
            synchronized (lock) {
                var claimed = new ArrayList<PendingMessage>();
                while (!pending.isEmpty() && (queueMode == QueueMode.ALL || claimed.isEmpty())) {
                    var entry = records.get(pending.removeFirst());
                    if (entry.phase != Phase.QUEUED || !entry.request.targetRunId().equals(activeRunId)
                            || entry.request.mode() != mode) {
                        continue;
                    }
                    entry.phase = Phase.CLAIMED;
                    claimed.add(new PendingMessage(entry.message, entry.request.inputId()));
                }
                return List.copyOf(claimed);
            }
        }

        @Override
        public boolean beginApply(String inputId) {
            synchronized (lock) {
                var entry = records.get(inputId);
                if (entry == null || entry.phase != Phase.CLAIMED) {
                    return false;
                }
                if (!accepting || !entry.request.targetRunId().equals(activeRunId)) {
                    entry.phase = Phase.NOT_APPLIED;
                    entry.reason = "run_closed";
                    return false;
                }
                entry.phase = Phase.APPLYING;
                return true;
            }
        }

        @Override
        public void applyFailed(String inputId, Throwable failure) {
            synchronized (lock) {
                var entry = records.get(inputId);
                if (entry != null && entry.phase == Phase.APPLYING) {
                    entry.phase = Phase.RECONCILIATION_REQUIRED;
                    entry.reason = "history_outcome_unknown";
                }
            }
        }

        @Override
        public void applyNotStarted(String inputId, Throwable failure) {
            synchronized (lock) {
                var entry = records.get(inputId);
                if (entry != null && entry.phase == Phase.APPLYING) {
                    entry.phase = Phase.NOT_APPLIED;
                    entry.reason = "message_start_failed";
                }
            }
        }
    }
}
