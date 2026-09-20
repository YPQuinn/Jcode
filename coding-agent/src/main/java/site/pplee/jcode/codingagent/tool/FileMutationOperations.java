package site.pplee.jcode.codingagent.tool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

/** Filesystem seam used to test the atomic mutation protocol deterministically. */
interface FileMutationOperations {
    Optional<BasicFileAttributes> readAttributesNoFollow(Path path) throws IOException;

    BasicFileAttributes readAttributes(Path path) throws IOException;

    Path toRealPath(Path path) throws IOException;

    void createDirectories(Path path) throws IOException;

    InputStream openInput(Path path) throws IOException;

    OutputStream openOutput(Path path) throws IOException;

    Path createTempFile(Path directory) throws IOException;

    Optional<Set<PosixFilePermission>> readPosixPermissions(Path path) throws IOException;

    void setPosixPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException;

    void moveAtomicReplace(Path source, Path target) throws IOException;

    void deleteIfExists(Path path) throws IOException;
}
