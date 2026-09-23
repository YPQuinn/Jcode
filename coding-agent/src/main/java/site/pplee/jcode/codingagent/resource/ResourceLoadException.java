package site.pplee.jcode.codingagent.resource;

/** Failure to build a complete resource snapshot whose selected base prompt is required. */
public final class ResourceLoadException extends RuntimeException {
    public ResourceLoadException(String message, Throwable cause) {
        super(message, cause);
    }

    public ResourceLoadException(String message) {
        super(message);
    }
}
