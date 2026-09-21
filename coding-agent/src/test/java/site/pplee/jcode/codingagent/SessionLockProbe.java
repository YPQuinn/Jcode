package site.pplee.jcode.codingagent;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Separate-JVM probe used to verify that another process cannot acquire a session writer lock. */
final class SessionLockProbe {
    private SessionLockProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            System.exit(64);
        }
        try (var channel = FileChannel.open(
                Path.of(arguments[0]), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var lock = channel.tryLock();
            if (lock == null) {
                System.exit(2);
            }
            lock.release();
        }
    }
}
