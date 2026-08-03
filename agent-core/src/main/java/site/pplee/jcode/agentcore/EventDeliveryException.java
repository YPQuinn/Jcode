package site.pplee.jcode.agentcore;

/**
 * Marks a failure in the event delivery infrastructure (the user event sink
 * threw synchronously or returned a failed stage). Distinct from normalized
 * tool/hook failures: an event delivery failure must escape the tool pipeline
 * as an infrastructure error instead of being encoded into a tool result the
 * model could retry.
 */
final class EventDeliveryException extends RuntimeException {
    EventDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
