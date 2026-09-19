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

    @Override
    public String toString() {
        return "CodingToolConfig[enabledTools=" + enabledTools
                + ", bash=" + (bash == null ? "absent" : "present")
                + ", search=" + (search == null ? "absent" : "present")
                + ", policy=present]";
    }
}
