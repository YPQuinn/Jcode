package site.pplee.jcode.codingagent.tool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

/** Delegating mutation filesystem used by focused protocol tests. */
class ForwardingFileMutationOperations implements FileMutationOperations {
    private final FileMutationOperations delegate = NioFileMutationOperations.INSTANCE;

    @Override
    public Optional<BasicFileAttributes> readAttributesNoFollow(Path path) throws IOException {
        return delegate.readAttributesNoFollow(path);
    }

    @Override
    public BasicFileAttributes readAttributes(Path path) throws IOException {
        return delegate.readAttributes(path);
    }

    @Override
    public Path toRealPath(Path path) throws IOException {
        return delegate.toRealPath(path);
    }

    @Override
    public void createDirectories(Path path) throws IOException {
        delegate.createDirectories(path);
    }

    @Override
    public InputStream openInput(Path path) throws IOException {
        return delegate.openInput(path);
    }

    @Override
    public OutputStream openOutput(Path path) throws IOException {
        return delegate.openOutput(path);
    }

    @Override
    public Path createTempFile(Path directory) throws IOException {
        return delegate.createTempFile(directory);
    }

    @Override
    public Optional<Set<PosixFilePermission>> readPosixPermissions(Path path) throws IOException {
        return delegate.readPosixPermissions(path);
    }

    @Override
    public void setPosixPermissions(Path path, Set<PosixFilePermission> permissions)
            throws IOException {
        delegate.setPosixPermissions(path, permissions);
    }

    @Override
    public void moveAtomicReplace(Path source, Path target) throws IOException {
        delegate.moveAtomicReplace(source, target);
    }

    @Override
    public void deleteIfExists(Path path) throws IOException {
        delegate.deleteIfExists(path);
    }
}
