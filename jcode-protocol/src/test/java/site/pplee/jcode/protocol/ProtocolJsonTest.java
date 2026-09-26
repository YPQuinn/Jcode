package site.pplee.jcode.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolJsonTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void commandsViewsAndErrorsRoundTripWithoutRuntimeTypes() throws Exception {
        var run = new RunCommand("cmd-1", "run-1", RunKind.PROMPT, "hello", "leaf-1");
        var input = new InputCommand("cmd-2", "input-1", "run-1", InputMode.FOLLOW_UP, "later");
        var runView = new RunView("session-1", "cmd-1", "run-1", RunStatus.COMPLETED,
                true, "STOP", "answer", false, null);
        var inputView = new InputView("session-1", "cmd-2", "input-1", "run-1",
                InputMode.FOLLOW_UP, InputStatus.APPLIED_TO_CONTEXT, "entry-1", null);
        var error = new ApiError(ErrorCode.IDEMPOTENCY_CONFLICT, "request differs");

        assertEquals(run, roundTrip(run, RunCommand.class));
        assertEquals(input, roundTrip(input, InputCommand.class));
        assertEquals(runView, roundTrip(runView, RunView.class));
        assertEquals(inputView, roundTrip(inputView, InputView.class));
        assertEquals(error, roundTrip(error, ApiError.class));
        assertFalse(run.toString().contains("hello"));
        assertFalse(input.toString().contains("later"));
    }

    @Test
    void rejectsInvalidShapesAtTheContractBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new RunCommand(
                "cmd", "run", RunKind.CONTINUE, "unexpected", null));
        assertThrows(IllegalArgumentException.class, () -> new RunCommand(
                "cmd", "run", RunKind.PROMPT, "", null));
        assertThrows(IllegalArgumentException.class, () -> new InputCommand(
                "cmd", "input", "run", InputMode.STEER, ""));
        assertThrows(IllegalArgumentException.class, () -> new InputView(
                "session", "cmd", "input", "run", InputMode.STEER,
                InputStatus.APPLIED_TO_CONTEXT, null, null));
    }

    private <T> T roundTrip(T value, Class<T> type) throws Exception {
        return mapper.readValue(mapper.writeValueAsBytes(value), type);
    }
}
