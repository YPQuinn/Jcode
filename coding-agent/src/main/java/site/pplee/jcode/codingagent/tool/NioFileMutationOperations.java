package site.pplee.jcode.codingagent.tool;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

/** NIO implementation of the local mutation filesystem seam. */
final class NioFileMutationOperations implements FileMutationOperations {
    static final NioFileMutationOperations INSTANCE = new NioFileMutationOperations();

    private NioFileMutationOperations() {
    }

    @Override
    public Optional<BasicFileAttributes> readAttributesNoFollow(Path path) throws IOException {
        try {
            return Optional.of(Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    @Override
    public BasicFileAttributes readAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class);
    }

    @Override
    public Path toRealPath(Path path) throws IOException {
        return path.toRealPath();
    }

    @Override
    public void createDirectories(Path path) throws IOException {
        Files.createDirectories(path);
    }

    @Override
    public InputStream openInput(Path path) throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public OutputStream openOutput(Path path) throws IOException {
        return Files.newOutputStream(path,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public Path createTempFile(Path directory) throws IOException {
        return Files.createTempFile(directory, ".jcode-", ".tmp");
    }

    @Override
    public Optional<Set<PosixFilePermission>> readPosixPermissions(Path path) throws IOException {
        var view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) {
            return Optional.empty();
        }
        return Optional.of(Set.copyOf(view.readAttributes().permissions()));
    }

    @Override
    public void setPosixPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        Files.setPosixFilePermissions(path, permissions);
    }

    @Override
    public void moveAtomicReplace(Path source, Path target) throws IOException {
        Files.move(source, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void deleteIfExists(Path path) throws IOException {
        Files.deleteIfExists(path);
    }
}
