package site.pplee.jcode.ai.tool;

/**
 * How strongly a {@link ToolInputConstraint} must be honored by a provider
 * adapter. {@link #PREFER} allows a documented safe fallback when the
 * capability or payload cannot be satisfied. {@link #REQUIRE} must fail
 * mapping instead of silently degrading.
 */
public enum Requirement {
    PREFER,
    REQUIRE
}
