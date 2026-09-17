package site.pplee.jcode.ai.message;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Sealed content model for a single message part. A message is an ordered
 * list of these; the {@code ai} layer never parses tool-call arguments.
 *
 * <p>Standard LLM content shared by the model-calling protocol:
 * a {@code Text | Thinking | ToolCall | Image} union. Images are
 * provider-neutral base64 input; adapters assemble any vendor data URL.
 */
public sealed interface Content
        permits Content.Text, Content.Thinking, Content.ToolCall, Content.Image {

    /**
     * Plain text. {@code replayState} is opaque same-model provider state;
     * {@code null} means there is nothing to replay.
     */
    record Text(String text, ModelReplayState replayState) implements Content {
        public Text {
            Objects.requireNonNull(text, "text must not be null");
        }

        /** Compatibility constructor: text with no replay state. */
        public Text(String text) {
            this(text, null);
        }
    }

    /**
     * Model reasoning / thinking trace. {@code replayState} is opaque
     * same-model provider state; {@code null} means there is nothing to replay.
     */
    record Thinking(String text, ModelReplayState replayState) implements Content {
        public Thinking {
            Objects.requireNonNull(text, "text must not be null");
        }

        /** Compatibility constructor: thinking text with no replay state. */
        public Thinking(String text) {
            this(text, null);
        }
    }

    /** A tool invocation requested by the model; arguments are an opaque {@link JsonNode}. */
    record ToolCall(
            String id,
            String name,
            JsonNode arguments
    ) implements Content {
        public ToolCall {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(arguments, "arguments must not be null");

            if (id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }

            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }
    }

    /**
     * User or tool-result image input stored as an immutable media type and
     * standard base64 payload. This is not a data URL and not a remote
     * source; {@link #toString()} reports only the type and encoded length.
     */
    record Image(String mediaType, String base64Data) implements Content {
        private static final Pattern IMAGE_MEDIA_TYPE = Pattern.compile(
                "image/[a-z0-9][a-z0-9!#$&^_.+-]*");

        public Image {
            Objects.requireNonNull(mediaType, "mediaType must not be null");
            Objects.requireNonNull(base64Data, "base64Data must not be null");
            if (mediaType.isBlank()) {
                throw new IllegalArgumentException("mediaType must not be blank");
            }
            if (base64Data.isBlank()) {
                throw new IllegalArgumentException("base64Data must not be blank");
            }
            String normalizedType = mediaType.strip().toLowerCase(Locale.ROOT);
            if (!IMAGE_MEDIA_TYPE.matcher(normalizedType).matches()) {
                throw new IllegalArgumentException(
                        "mediaType must be a valid image/* type: " + mediaType);
            }
            if (looksLikeDataUrl(base64Data)) {
                throw new IllegalArgumentException(
                        "base64Data must be standard base64, not a data URL");
            }
            try {
                Base64.getDecoder().decode(base64Data);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "base64Data must be standard base64", e);
            }
            mediaType = normalizedType;
        }

        @Override
        public String toString() {
            return "Image[mediaType=" + mediaType + ", encodedLength=" + base64Data.length() + "]";
        }

        private static boolean looksLikeDataUrl(String value) {
            String trimmed = value.strip();
            return trimmed.regionMatches(true, 0, "data:", 0, 5);
        }
    }
}
