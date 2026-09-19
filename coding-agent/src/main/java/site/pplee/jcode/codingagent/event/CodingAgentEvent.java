package site.pplee.jcode.codingagent.event;

import site.pplee.jcode.agentcore.event.AgentEvent;
import site.pplee.jcode.codingagent.CodingAgentRunResult;
import site.pplee.jcode.codingagent.internal.SnapshotMapper;

import java.util.Objects;

/** Product event protocol emitted by a headless coding-agent session. */
public sealed interface CodingAgentEvent
        permits CodingAgentEvent.RuntimeEvent, CodingAgentEvent.RunCompleted {

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
}
