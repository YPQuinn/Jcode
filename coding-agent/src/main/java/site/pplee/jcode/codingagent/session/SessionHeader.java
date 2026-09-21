package site.pplee.jcode.codingagent.session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable identity and creation metadata for one session. */
public record SessionHeader(UUID id, Instant timestamp, Path cwd) {
    /** JSONL format marker used by the first line. */
    public static final String TYPE = "session";
    /** First Jcode session format version. */
    public static final int VERSION = 1;

    public SessionHeader {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        cwd = Objects.requireNonNull(cwd, "cwd must not be null").toAbsolutePath().normalize();
    }

    /** Fixed record type written by the version-one codec. */
    public String type() {
        return TYPE;
    }

    /** Fixed Jcode session format version. */
    public int version() {
        return VERSION;
    }
}
