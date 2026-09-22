package site.pplee.jcode.codingagent.event;

import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.codingagent.CodingAgentRunResult;
import site.pplee.jcode.codingagent.internal.SnapshotMapper;
import site.pplee.jcode.codingagent.compaction.SummaryCause;
import site.pplee.jcode.ai.message.Usage;
import site.pplee.jcode.ai.model.ModelRef;

import java.util.Objects;

/** Product event protocol emitted by a headless coding-agent session. */
public sealed interface CodingAgentEvent
        permits CodingAgentEvent.RuntimeEvent, CodingAgentEvent.RunCompleted,
        CodingAgentEvent.SummaryStarted, CodingAgentEvent.SummaryCompleted,
        CodingAgentEvent.SummaryFailed, CodingAgentEvent.SummaryCancelled {

    /** Snapshot of a non-terminal Agent runtime event. */
    record RuntimeEvent(AgentEvent event) implements CodingAgentEvent {
        public RuntimeEvent {
            Objects.requireNonNull(event, "event must not be null");
            if (event instanceof AgentEvent.AgentCompleted) {
                throw new IllegalArgumentException(
                        "AgentCompleted must be represented by RunCompleted");
            }
            event = SnapshotMapper.runtimeEvent(event);
        }

        @Override
        public AgentEvent event() {
            return SnapshotMapper.runtimeEvent(event);
        }
    }

    /** Product-safe completion event replacing the runtime AgentCompleted payload. */
    record RunCompleted(CodingAgentRunResult result) implements CodingAgentEvent {
        public RunCompleted {
            result = SnapshotMapper.runResult(
                    Objects.requireNonNull(result, "result must not be null"));
        }

        @Override
        public CodingAgentRunResult result() {
            return SnapshotMapper.runResult(result);
        }
    }

    record SummaryStarted(SummaryCause cause, ModelRef model) implements CodingAgentEvent {
        public SummaryStarted {
            Objects.requireNonNull(cause, "cause must not be null");
            Objects.requireNonNull(model, "model must not be null");
        }
    }

    record SummaryCompleted(
            SummaryCause cause,
            String entryId,
            ModelRef model,
            Usage usage
    ) implements CodingAgentEvent {
        public SummaryCompleted {
            Objects.requireNonNull(cause, "cause must not be null");
            Objects.requireNonNull(entryId, "entryId must not be null");
            Objects.requireNonNull(model, "model must not be null");
            Objects.requireNonNull(usage, "usage must not be null");
        }
    }

    record SummaryFailed(SummaryCause cause, String category) implements CodingAgentEvent {
        public SummaryFailed {
            Objects.requireNonNull(cause, "cause must not be null");
            Objects.requireNonNull(category, "category must not be null");
        }
    }

    record SummaryCancelled(SummaryCause cause) implements CodingAgentEvent {
        public SummaryCancelled {
            Objects.requireNonNull(cause, "cause must not be null");
        }
    }
}
