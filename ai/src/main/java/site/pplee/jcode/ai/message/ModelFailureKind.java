package site.pplee.jcode.ai.message;

/** Provider-neutral classification for model failures that require caller action. */
public enum ModelFailureKind {
    /** The request input exceeded the model's available context window. */
    CONTEXT_OVERFLOW
}
