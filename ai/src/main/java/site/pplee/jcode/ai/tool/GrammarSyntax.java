package site.pplee.jcode.ai.tool;

/**
 * Provider-neutral grammar encodings a tool may offer for constrained
 * sampling. Adapters choose among the declared variants and own any
 * provider-specific wire tokens themselves.
 */
public enum GrammarSyntax {
    LARK,
    REGEX
}
