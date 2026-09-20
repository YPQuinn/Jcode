package site.pplee.jcode.codingagent.tool;

/** Idempotent cancellation handle for a scheduled task. */
@FunctionalInterface
interface ScheduledTask {
    void cancel();
}
