package site.pplee.jcode.codingagent.tool;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable built-in tool selection and authorization configuration. */
public record CodingToolConfig(
        Set<CodingTool> enabledTools,
        BashConfig bash,
        SearchConfig search,
        CodingToolPolicy policy
) {
    public CodingToolConfig {
        Objects.requireNonNull(enabledTools, "enabledTools must not be null");
        if (enabledTools.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("enabledTools must not contain null");
        }
        enabledTools = enabledTools.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(enabledTools));
        policy = policy == null ? CodingToolPolicy.allowAll() : policy;
    }

    /** Preserve the original session behavior: enable only the read tool. */
    public static CodingToolConfig readOnly() {
        return new CodingToolConfig(Set.of(CodingTool.READ), null, null,
                CodingToolPolicy.allowAll());
    }

    /** Explicitly enable the minimal local coding loop, including Bash execution. */
    public static CodingToolConfig coding(BashConfig bash) {
        return new CodingToolConfig(
                Set.of(CodingTool.READ, CodingTool.WRITE, CodingTool.EDIT, CodingTool.BASH),
                Objects.requireNonNull(bash, "bash must not be null"),
                null,
                CodingToolPolicy.allowAll());
    }

    /** Explicitly enable all local coding and search tools. */
    public static CodingToolConfig codingWithSearch(BashConfig bash, SearchConfig search) {
        return new CodingToolConfig(
                EnumSet.allOf(CodingTool.class),
                Objects.requireNonNull(bash, "bash must not be null"),
                Objects.requireNonNull(search, "search must not be null"),
                CodingToolPolicy.allowAll());
    }

    @Override
    public String toString() {
        return "CodingToolConfig[enabledTools=" + enabledTools
                + ", bash=" + (bash == null ? "absent" : "present")
                + ", search=" + (search == null ? "absent" : "present")
                + ", policy=present]";
    }
}
