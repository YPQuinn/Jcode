package site.pplee.jcode.codingagent.resource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Single bounded UTF-8 reader shared by resource metadata and on-demand expansion. */
final class ResourceTextReader {
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private ResourceTextReader() {
    }

    static String read(Path path) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("resource exceeds the 4 MiB read limit");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new IOException("resource is not valid UTF-8", failure);
        }
    }
}
