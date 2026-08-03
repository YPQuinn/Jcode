package site.pplee.jcode.ai.model;

/**
 * Provider-neutral thinking or reasoning intensity requested for a model call.
 * Adapters translate these absolute values to provider-specific options.
 * Unsupported values must be handled through the model stream error protocol,
 * never by throwing synchronously from the client boundary.
 */
public enum ThinkingLevel {
    /** Do not send an explicit thinking preference; use the provider default. */
    PROVIDER_DEFAULT,
    /** Explicitly request thinking to be disabled when the model supports it. */
    OFF,
    /** Request the provider's minimal thinking intensity. */
    MINIMAL,
    /** Request low thinking intensity. */
    LOW,
    /** Request medium thinking intensity. */
    MEDIUM,
    /** Request high thinking intensity. */
    HIGH,
    /** Request an extended high thinking intensity when supported. */
    XHIGH,
    /** Request the maximum thinking intensity when supported. */
    MAX
}
