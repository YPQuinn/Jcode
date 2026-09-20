package site.pplee.jcode.codingagent.context;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable effective project instruction source and its decoded body. */
public record ProjectContextFile(
        ProjectContextScope scope,
        Path discoveredPath,
        Path physicalPath,
        String content,
        long rawBytes
) {
    public ProjectContextFile {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(discoveredPath, "discoveredPath must not be null");
        Objects.requireNonNull(physicalPath, "physicalPath must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (rawBytes < 0) {
            throw new IllegalArgumentException("rawBytes must not be negative");
        }
    }

    @Override
    public String toString() {
        return "ProjectContextFile[scope=" + scope + ", discoveredPath=redacted, physicalPath=redacted"
                + ", content=redacted, rawBytes=" + rawBytes + ']';
    }
}
