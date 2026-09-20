package site.pplee.jcode.codingagent.tool;

import java.time.Duration;

/** Injectable monotonic deadline scheduler for process and update coordination. */
interface TaskScheduler extends AutoCloseable {
    ScheduledTask schedule(Duration delay, Runnable task);

    @Override
    void close();
}
