package site.pplee.jcode.agentcore.queue;

/**
 * Controls how much one {@link PendingMessageSource#drain} removes.
 * {@link #ALL} drains the whole queue; {@link #ONE_AT_A_TIME} drains the
 * oldest only.
 */
public enum QueueMode {
    ALL,
    ONE_AT_A_TIME
}
