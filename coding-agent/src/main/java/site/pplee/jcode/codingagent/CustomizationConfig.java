package site.pplee.jcode.codingagent;

import site.pplee.jcode.codingagent.extension.CodingExtension;
import site.pplee.jcode.codingagent.resource.ResourceConfig;

import java.util.List;
import java.util.Objects;

/** Immutable grouping of text-resource and borrowed Java-extension inputs. */
public record CustomizationConfig(
        ResourceConfig resources,
        List<CodingExtension> extensions
) {
    public CustomizationConfig {
        resources = resources == null ? ResourceConfig.disabled() : resources;
        extensions = List.copyOf(extensions == null ? List.of() : extensions);
        if (extensions.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("extensions must not contain null");
        }
    }

    public static CustomizationConfig none() {
        return new CustomizationConfig(ResourceConfig.disabled(), List.of());
    }

    @Override
    public String toString() {
        return "CustomizationConfig[resources=" + resources
                + ", extensions=" + extensions.size() + ']';
    }
}
